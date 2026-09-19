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
import org.json.JSONObject

private const val DEEPGRAM_WS_ENDPOINT = "wss://api.deepgram.com/v1/listen"
private const val TAG = "DeepgramStream"

/**
 * One live transcription session over Deepgram's streaming WebSocket API.
 *
 * Raw 16 kHz mono PCM frames go up as binary messages while recording; Deepgram sends back
 * JSON "Results" as it goes. Each result is either interim (a guess at the phrase currently
 * being spoken, which later results revise) or final (locked in). We keep the finals joined
 * together and show the latest interim after them, so the user sees text as they talk.
 *
 * Audio is billed once, by duration, so this costs no more than the prerecorded endpoint.
 * [onTranscript] is invoked on the main thread with (finalizedText, currentInterim).
 */
class DeepgramStream(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val language: String,
    private val onTranscript: (finalText: String, interim: String) -> Unit,
    /** Override for tests (points the client at a mock server). */
    private val endpoint: String = DEEPGRAM_WS_ENDPOINT,
) : LiveTranscriber {
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val finals = StringBuilder()   // touched only on OkHttp's reader thread
    @Volatile private var finalSnapshot = ""
    private val done = CompletableDeferred<String>()

    override fun start() {
        // Nova-3 uses language=multi for multilingual auto-detection.
        val lang = if (language == "auto") "multi" else language
        val url = "$endpoint?model=nova-3&smart_format=true&language=$lang" +
            "&encoding=linear16&sample_rate=16000&channels=1&interim_results=true"
        val request = Request.Builder().url(url).header("Authorization", "Token $apiKey").build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Stream failed (${response?.code})", t)
                done.completeExceptionally(t)
            }

            // Deepgram closes the socket itself once it has flushed everything after CloseStream.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.complete(finalSnapshot)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(finalSnapshot)
            }
        })
    }

    /** Sends [len] bytes of PCM. Copies the buffer, so the caller may reuse it. */
    override fun send(pcm: ByteArray, len: Int) {
        ws?.send(pcm.toByteString(0, len))
    }

    /**
     * Tells Deepgram no more audio is coming, waits for it to flush the last results and
     * close, and returns the full finalized transcript. Throws if the stream had failed.
     */
    override suspend fun finish(): String {
        ws?.send("""{"type":"CloseStream"}""")
        return withTimeoutOrNull(8_000) { done.await() } ?: run {
            Log.w(TAG, "Timed out waiting for Deepgram to close; using text so far")
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
        if (json.optString("type") != "Results") return  // Metadata / SpeechStarted etc.
        val transcript = json.optJSONObject("channel")
            ?.optJSONArray("alternatives")?.optJSONObject(0)
            ?.optString("transcript")?.trim() ?: return
        val interim: String
        if (json.optBoolean("is_final")) {
            if (transcript.isNotEmpty()) {
                if (finals.isNotEmpty()) finals.append(' ')
                finals.append(transcript)
            }
            finalSnapshot = finals.toString()
            interim = ""
        } else {
            interim = transcript
        }
        val finalText = finalSnapshot
        main.post { onTranscript(finalText, interim) }
    }
}
