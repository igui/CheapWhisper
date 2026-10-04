package com.example.smartnotetaker

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

// Transcription-only Realtime session (GA interface: `session.update` with type "transcription",
// no OpenAI-Beta header).
private const val OPENAI_WS_ENDPOINT = "wss://api.openai.com/v1/realtime?intent=transcription"
private const val TAG = "OpenAiStream"

/**
 * Cheapest Realtime-capable OpenAI STT model (~$0.003 per audio minute). It transcribes each
 * turn once the turn is committed, so text arrives per utterance (after each pause) rather than
 * word by word. `gpt-live-transcribe` (~$0.017/min) streams deltas while you speak; if you switch
 * to it, its language hint goes in a `languages` array instead of `language` (handled below).
 */
private const val MODEL = "gpt-4o-mini-transcribe"

/** OpenAI's Realtime API accepts PCM16 only at this rate; the keyboard records at 16 kHz. */
private const val INPUT_RATE = 16_000
private const val OPENAI_RATE = 24_000

/**
 * One live transcription session over OpenAI's Realtime WebSocket API.
 *
 * Audio goes up as base64 `input_audio_buffer.append` events (upsampled from 16 kHz to the
 * 24 kHz OpenAI requires). Server VAD splits speech at pauses and commits each utterance, so
 * text appears while the user is still talking; [finish] commits whatever is left in the
 * buffer (the tail VAD has not cut yet) and waits for every outstanding item to be
 * transcribed. Each completed item is locked in; the delta text of the item in progress is
 * shown as interim.
 *
 * [onTranscript] is invoked on the main thread with (finalizedText, currentInterim).
 */
class OpenAiStream(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val language: String,
    private val onTranscript: (finalText: String, interim: String) -> Unit,
    /** Override for tests (points the client at a mock server). */
    private val endpoint: String = OPENAI_WS_ENDPOINT,
) : LiveTranscriber {
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val done = CompletableDeferred<String>()

    // --- Reader-thread state (OkHttp delivers messages on one thread) ---
    private val finals = StringBuilder()
    @Volatile private var finalSnapshot = ""
    private val interim = StringBuilder()
    private var interimItemId: String? = null
    /** Items committed (by VAD or by us) whose transcription has not arrived yet. */
    private val pendingItems = HashSet<String>()
    /** Set once the server has reacted (committed event or error) to the commit sent by [finish]. */
    private var finalCommitAcked = false
    @Volatile private var finishing = false

    // --- Send-side state, guarded by [sendLock] ---
    private val sendLock = Any()
    private var sessionReady = false               // session.update has been sent
    private val preOpenAudio = ByteArrayOutputStream()  // 24 kHz PCM captured before the socket opened
    private var commitRequested = false            // finish() ran before the socket opened

    // --- Recorder-thread resampler state (send() is only ever called from one thread) ---
    private var prevSample = 0     // last input sample of the previous buffer
    private var phase = 1.0        // next read position, in input samples, relative to prevSample

    override fun start() {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(sendLock) {
                    webSocket.send(sessionConfig().toString())
                    sessionReady = true
                    if (preOpenAudio.size() > 0) {
                        webSocket.send(appendEvent(preOpenAudio.toByteArray()))
                        preOpenAudio.reset()
                    }
                    if (commitRequested) webSocket.send(COMMIT_EVENT)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Stream failed (${response?.code})", t)
                done.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.completeExceptionally(IOException("OpenAi closed before transcription completion (code $code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.completeExceptionally(IOException("OpenAi closed before transcription completion (code $code)"))
            }
        })
    }

    /** Sends [len] bytes of 16 kHz PCM, upsampled to 24 kHz. Copies the data; the caller may reuse [pcm]. */
    override fun send(pcm: ByteArray, len: Int) {
        val socket = ws ?: return
        val audio = upsample(pcm, len)
        if (audio.isEmpty()) return
        synchronized(sendLock) {
            if (sessionReady) socket.send(appendEvent(audio)) else preOpenAudio.write(audio)
        }
    }

    /**
     * Commits the remaining audio, waits for OpenAI to transcribe every outstanding item and
     * returns the full finalized transcript. Throws if the session had failed.
     */
    override suspend fun finish(): String {
        finishing = true
        synchronized(sendLock) {
            if (sessionReady) ws?.send(COMMIT_EVENT) else commitRequested = true
        }
        val result = withTimeoutOrNull(8_000) { done.await() } ?: run {
            Log.w(TAG, "Timed out waiting for OpenAI to finish; falling back to recorded audio")
            ws?.cancel()
            throw IOException("Timed out waiting for transcription completion")
        }
        ws?.close(1000, null)  // OpenAI keeps the session open until the client closes it.
        return result
    }

    /** Drops the connection immediately, discarding any pending results. */
    override fun cancel() {
        done.complete(finalSnapshot)
        ws?.cancel()
    }

    private fun sessionConfig(): JSONObject {
        val transcription = JSONObject().put("model", MODEL)
        if (language != "auto") {
            if (MODEL == "gpt-live-transcribe") transcription.put("languages", JSONArray().put(language))
            else transcription.put("language", language)
        }
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", OPENAI_RATE))
            .put("noise_reduction", JSONObject().put("type", "near_field"))
            .put("transcription", transcription)
            // Server VAD commits each utterance as the user pauses, so text shows up while they
            // are still speaking; the tail after the last pause is committed by finish().
            .put("turn_detection", JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.5)
                .put("prefix_padding_ms", 300)
                .put("silence_duration_ms", 500))
        return JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject()
                .put("type", "transcription")
                .put("audio", JSONObject().put("input", input)))
    }

    private fun appendEvent(pcm24k: ByteArray): String =
        "{\"type\":\"input_audio_buffer.append\",\"audio\":\"" +
            Base64.encodeToString(pcm24k, Base64.NO_WRAP) + "\"}"

    private fun handle(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        when (json.optString("type")) {
            "input_audio_buffer.committed" -> {
                json.optString("item_id").takeIf { it.isNotEmpty() }?.let { pendingItems.add(it) }
                // A VAD commit landing in the few ms between finish() and our own commit is
                // counted as the ack too; at most a round-trip's worth of silence is lost then.
                if (finishing) finalCommitAcked = true
                maybeDone()
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val itemId = json.optString("item_id")
                if (itemId != interimItemId) { interim.setLength(0); interimItemId = itemId }
                interim.append(json.optString("delta"))
                publish(interim.toString().trim())
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = json.optString("transcript").trim()
                if (transcript.isNotEmpty()) {
                    if (finals.isNotEmpty()) finals.append(' ')
                    finals.append(transcript)
                    finalSnapshot = finals.toString()
                }
                itemFinished(json.optString("item_id"))
            }
            "conversation.item.input_audio_transcription.failed" -> {
                Log.w(TAG, "Transcription failed: ${json.optJSONObject("error")?.optString("message")}")
                done.completeExceptionally(IOException("OpenAI could not transcribe an audio item"))
                ws?.cancel()
            }
            "error" -> {
                val message = json.optJSONObject("error")?.optString("message") ?: text
                val code = json.optJSONObject("error")?.optString("code").orEmpty()
                val emptyCommit = code == "input_audio_buffer_commit_empty" ||
                    (code.isBlank() && message.startsWith("Error committing input audio buffer:") &&
                        ("buffer too small" in message || "buffer is empty" in message))
                if (finishing && emptyCommit) {
                    // Typically "buffer is empty"/"buffer too small": VAD had already committed
                    // everything, so there is no extra item to wait for.
                    Log.i(TAG, "Error after final commit (ignored): $message")
                    finalCommitAcked = true
                    maybeDone()
                } else {
                    Log.w(TAG, "Session error: $message")
                    done.completeExceptionally(IllegalStateException("OpenAI realtime error: $message"))
                    ws?.cancel()
                }
            }
            // session.created / session.updated / speech_started / speech_stopped / item.created
            else -> Unit
        }
    }

    private fun itemFinished(itemId: String) {
        pendingItems.remove(itemId)
        if (itemId == interimItemId) { interim.setLength(0); interimItemId = null }
        publish(interim.toString().trim())
        maybeDone()
    }

    private fun maybeDone() {
        if (finishing && finalCommitAcked && pendingItems.isEmpty()) done.complete(finalSnapshot)
    }

    private fun publish(interimText: String) {
        val finalText = finalSnapshot
        main.post { onTranscript(finalText, interimText) }
    }

    /**
     * Linear-interpolation upsampler 16 kHz -> 24 kHz (ratio 2:3). Keeps the previous buffer's
     * last sample and the fractional read position so consecutive buffers join seamlessly.
     */
    private fun upsample(pcm: ByteArray, len: Int): ByteArray {
        val n = len / 2
        if (n == 0) return ByteArray(0)
        val step = INPUT_RATE.toDouble() / OPENAI_RATE   // 2/3 input sample per output sample
        val out = ByteArray(2 * (3 * n / 2 + 2))
        var o = 0
        var p = phase   // position in the virtual array [prevSample, s0, s1, ..., s(n-1)]
        while (p < n) {
            val i = p.toInt()
            val frac = p - i
            val a = if (i == 0) prevSample else sample(pcm, i - 1)
            val b = sample(pcm, i)
            val v = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767)
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v shr 8) and 0xFF).toByte()
            p += step
        }
        phase = p - n
        prevSample = sample(pcm, n - 1)
        return out.copyOf(o)
    }

    private fun sample(pcm: ByteArray, index: Int): Int =
        ((pcm[2 * index].toInt() and 0xFF) or (pcm[2 * index + 1].toInt() shl 8)).toShort().toInt()

    private companion object {
        const val COMMIT_EVENT = """{"type":"input_audio_buffer.commit"}"""
    }
}
