package com.example.smartnotetaker

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val ASSEMBLYAI_WS_ENDPOINT = "wss://streaming.assemblyai.com/v3/ws"
private const val TAG = "AssemblyAiStream"

/** 50 ms of 16 kHz mono 16-bit PCM: the smallest audio message the API accepts. */
private const val MIN_CHUNK_BYTES = 1600

/** Languages the universal-streaming-multilingual model can be steered towards. */
private val MULTILINGUAL_CODES = setOf("en", "es", "de", "fr", "pt", "it")

/**
 * One live transcription session over AssemblyAI's Universal-Streaming WebSocket API (v3).
 *
 * Raw 16 kHz mono PCM goes up as binary messages while recording; AssemblyAI answers with
 * JSON "Turn" messages. A turn is one utterance: while it is being spoken we get partial
 * turns (`end_of_turn=false`, revised as more audio arrives); when it ends we get an
 * unformatted final (`end_of_turn=true, turn_is_formatted=false`) followed by a formatted
 * one (`turn_is_formatted=true`, same `turn_order`) with punctuation and casing. We keep
 * the formatted finals joined together and show the current turn's text after them.
 *
 * Model: `universal-streaming-english` for English, `universal-streaming-multilingual`
 * (en/es/de/fr/pt/it, detected per turn) otherwise. Billed on session duration, not audio
 * duration, at $0.15/h, so keeping the socket open only while recording matters.
 * [onTranscript] is invoked on the main thread with (finalizedText, currentInterim).
 */
class AssemblyAiStream(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val language: String,
    private val onTranscript: (finalText: String, interim: String) -> Unit,
    /** Override for tests (points the client at a mock server). */
    private val endpoint: String = ASSEMBLYAI_WS_ENDPOINT,
) : LiveTranscriber {
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val finals = StringBuilder()   // touched only on OkHttp's reader thread
    @Volatile private var finalSnapshot = ""
    private val done = CompletableDeferred<String>()

    // The current turn's unformatted final, held until its formatted twin arrives (or the
    // session ends without one, in which case it is folded into the finals as-is).
    private var pendingOrder = -1
    private var pendingText = ""

    // Recorder buffers can be under 50 ms; the API rejects messages shorter than that, so
    // small ones are coalesced here. Guarded by [pendingAudio] itself.
    private val pendingAudio = java.io.ByteArrayOutputStream(MIN_CHUNK_BYTES * 2)

    override fun start() {
        val params = linkedMapOf(
            "sample_rate" to "16000",
            "encoding" to "pcm_s16le",
            "format_turns" to "true",
        )
        val lang = language.lowercase()
        if (lang == "en") {
            params["speech_model"] = "universal-streaming-english"
        } else {
            params["speech_model"] = "universal-streaming-multilingual"
            if (lang in MULTILINGUAL_CODES) {
                params["language_codes"] = JSONArray().put(lang).toString()
            } else if (lang != "auto") {
                Log.w(TAG, "Language '$language' not supported by streaming; using auto-detect")
            }
        }
        val url = params.entries.joinToString("&", prefix = "$endpoint?") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        // Plain API key, no "Bearer" prefix (a temporary token would go in ?token= instead).
        val request = Request.Builder().url(url).header("Authorization", apiKey).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Stream failed (${response?.code})", t)
                done.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                complete()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                complete()
            }
        })
    }

    /** Sends [len] bytes of PCM. Copies the buffer, so the caller may reuse it. */
    override fun send(pcm: ByteArray, len: Int) {
        val chunk: ByteArray
        synchronized(pendingAudio) {
            pendingAudio.write(pcm, 0, len)
            if (pendingAudio.size() < MIN_CHUNK_BYTES) return
            chunk = pendingAudio.toByteArray()
            pendingAudio.reset()
        }
        ws?.send(chunk.toByteString())
    }

    /**
     * Tells AssemblyAI no more audio is coming, waits for it to flush the last turn and send
     * its Termination message, and returns the full finalized transcript. Throws if the
     * stream had failed.
     */
    override suspend fun finish(): String {
        flushAudio()
        ws?.send("""{"type":"Terminate"}""")
        return withTimeoutOrNull(8_000) { done.await() } ?: run {
            Log.w(TAG, "Timed out waiting for AssemblyAI to terminate; using text so far")
            ws?.cancel()
            finalSnapshot
        }
    }

    /** Drops the connection immediately, discarding any pending results. */
    override fun cancel() {
        ws?.cancel()
        done.complete(finalSnapshot)
    }

    /** Sends whatever audio is still coalescing, padded with silence up to the 50 ms minimum. */
    private fun flushAudio() {
        val chunk: ByteArray
        synchronized(pendingAudio) {
            if (pendingAudio.size() == 0) return
            while (pendingAudio.size() < MIN_CHUNK_BYTES) pendingAudio.write(0)
            chunk = pendingAudio.toByteArray()
            pendingAudio.reset()
        }
        ws?.send(chunk.toByteString())
    }

    private fun handle(webSocket: WebSocket, text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        when (json.optString("type")) {
            "Turn" -> handleTurn(json)
            "Termination" -> {
                complete()
                webSocket.close(1000, null)
            }
            else -> if (json.has("error")) {  // e.g. bad params or invalid key
                val message = json.optString("error")
                Log.w(TAG, "Server error: $message")
                done.completeExceptionally(RuntimeException("AssemblyAI: $message"))
                webSocket.cancel()
            }
            // Begin / SpeechStarted / Heartbeat etc. are ignored.
        }
    }

    private fun handleTurn(json: JSONObject) {
        val transcript = json.optString("transcript").trim()
        val order = json.optInt("turn_order", -1)
        val interim: String
        if (!json.optBoolean("end_of_turn")) {
            interim = transcript
        } else if (json.optBoolean("turn_is_formatted")) {
            if (order == pendingOrder) pendingText = ""
            appendFinal(transcript)
            interim = ""
        } else {
            // Unformatted final: keep showing it as interim until the formatted twin lands.
            pendingOrder = order
            pendingText = transcript
            interim = transcript
        }
        val finalText = finalSnapshot
        main.post { onTranscript(finalText, interim) }
    }

    private fun appendFinal(transcript: String) {
        if (transcript.isNotEmpty()) {
            if (finals.isNotEmpty()) finals.append(' ')
            finals.append(transcript)
        }
        finalSnapshot = finals.toString()
    }

    /** Called on the reader thread once no more turns can arrive. */
    private fun complete() {
        if (pendingText.isNotEmpty()) {  // formatted twin never came; keep the plain text
            appendFinal(pendingText)
            pendingText = ""
        }
        done.complete(finalSnapshot)
    }
}
