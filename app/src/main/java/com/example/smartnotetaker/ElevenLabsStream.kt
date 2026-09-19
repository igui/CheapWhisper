package com.example.smartnotetaker

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val ELEVENLABS_WS_ENDPOINT = "wss://api.elevenlabs.io/v1/speech-to-text/realtime"
private const val SAMPLE_RATE = 16_000
private const val TAG = "ElevenLabsStream"

/**
 * One live transcription session over ElevenLabs' Scribe v2 Realtime WebSocket API.
 *
 * Audio goes up as JSON `input_audio_chunk` messages carrying base64 16 kHz mono PCM, which is
 * exactly what the keyboard records, so nothing is converted. The server streams back
 * `partial_transcript` messages (a revisable guess at the segment currently being spoken) and,
 * with `commit_strategy=vad`, a `committed_transcript` each time it hears a pause: that segment
 * is locked in and the partial starts over. We keep the committed segments joined together and
 * show the latest partial after them, so the user sees text as they talk.
 *
 * Billed by audio duration (Scribe v2 Realtime is priced per hour of audio, above the batch
 * Scribe rate). [onTranscript] is invoked on the main thread with (finalizedText, currentInterim).
 */
class ElevenLabsStream(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val language: String,
    private val onTranscript: (finalText: String, interim: String) -> Unit,
    /** Override for tests (points the client at a mock server). */
    private val endpoint: String = ELEVENLABS_WS_ENDPOINT,
) : LiveTranscriber {
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val finals = StringBuilder()   // touched only on OkHttp's reader thread
    @Volatile private var finalSnapshot = ""
    @Volatile private var finishing = false
    private val done = CompletableDeferred<String>()

    override fun start() {
        // Omitting language_code lets Scribe auto-detect (there is no explicit "auto" value).
        val lang = if (language == "auto") "" else "&language_code=$language"
        val url = "$endpoint?model_id=scribe_v2_realtime&audio_format=pcm_16000" +
            "&commit_strategy=vad&vad_silence_threshold_secs=1.0$lang"
        val request = Request.Builder().url(url).header("xi-api-key", apiKey).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Stream failed (${response?.code})", t)
                done.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.complete(finalSnapshot)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(finalSnapshot)
            }
        })
    }

    /** Sends [len] bytes of PCM. Base64-encoding copies the buffer, so the caller may reuse it. */
    override fun send(pcm: ByteArray, len: Int) {
        val socket = ws ?: return
        val msg = JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.encodeToString(pcm, 0, len, Base64.NO_WRAP))
            .put("commit", false)
            .put("sample_rate", SAMPLE_RATE)
        socket.send(msg.toString())
    }

    /**
     * Sends a final manual commit so Scribe flushes whatever the VAD has not committed yet,
     * waits for that last `committed_transcript`, closes the socket and returns the full
     * finalized transcript. Throws if the stream had failed.
     */
    override suspend fun finish(): String {
        finishing = true
        val commit = JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", "")
            .put("commit", true)
            .put("sample_rate", SAMPLE_RATE)
        ws?.send(commit.toString())
        return withTimeoutOrNull(8_000) { done.await() } ?: run {
            Log.w(TAG, "Timed out waiting for ElevenLabs to flush; using text so far")
            ws?.cancel()
            finalSnapshot
        }
    }

    /** Drops the connection immediately, discarding any pending results. */
    override fun cancel() {
        ws?.cancel()
        done.complete(finalSnapshot)
    }

    private fun handle(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        when (val type = json.optString("message_type")) {
            "partial_transcript" -> {
                val interim = json.optString("text").trim()
                val finalText = finalSnapshot
                main.post { onTranscript(finalText, interim) }
            }
            "committed_transcript" -> {
                val segment = json.optString("text").trim()
                if (segment.isNotEmpty()) {
                    if (finals.isNotEmpty()) finals.append(' ')
                    finals.append(segment)
                }
                finalSnapshot = finals.toString()
                val finalText = finalSnapshot
                main.post { onTranscript(finalText, "") }
                if (finishing) flushed()
            }
            // Our closing commit found nothing new to transcribe (the VAD already committed it
            // all, or the user never spoke): the transcript so far is complete.
            "insufficient_audio_activity", "commit_throttled" -> {
                if (finishing) flushed() else Log.w(TAG, "$type: $text")
            }
            "session_started", "committed_transcript_with_timestamps",
            "committed_transcript_entities" -> Unit
            "warning" -> Log.w(TAG, "Warning: $text")
            "" -> Unit
            // Everything else the server sends is an error (auth_error, quota_exceeded,
            // rate_limited, queue_overflow, chunk_size_exceeded, transcriber_error, ...).
            else -> {
                Log.w(TAG, "Stream error: $text")
                done.completeExceptionally(RuntimeException("ElevenLabs $type: $text"))
                ws?.cancel()
            }
        }
    }

    /** The server has flushed everything after our closing commit; hang up and finish. */
    private fun flushed() {
        done.complete(finalSnapshot)
        ws?.close(1000, null)
    }
}
