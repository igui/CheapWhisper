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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val SONIOX_WS_ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"
private const val SONIOX_RT_MODEL = "stt-rt-v5"
private const val TAG = "SonioxStream"

/** Control tokens Soniox emits that are not speech: endpoint marker and manual-finalize ack. */
private val CONTROL_TOKENS = setOf("<end>", "<fin>")

/**
 * One live transcription session over Soniox's real-time WebSocket API
 * (https://soniox.com/docs/stt/api-reference/websocket-api).
 *
 * The first message on the socket is a JSON config carrying the API key (there is no auth
 * header); after that raw 16 kHz mono PCM goes up as binary frames. Soniox answers with
 * JSON `{"tokens": [...]}` messages. Final tokens (`is_final: true`) are sent exactly once
 * and never revised, so they are appended to the running transcript as-is (token texts
 * carry their own leading spaces). Non-final tokens are the model's current guess for the
 * phrase in progress and are re-sent in full on every response, so they replace the
 * previous interim rather than extend it.
 *
 * End of audio is an empty binary frame; the server flushes, replies with
 * `{"finished": true}` and closes. Billed on audio duration, $0.12/h.
 * [onTranscript] is invoked on the main thread with (finalizedText, currentInterim).
 */
class SonioxStream(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val language: String,
    private val onTranscript: (finalText: String, interim: String) -> Unit,
    /** Override for tests (points the client at a mock server). */
    private val endpoint: String = SONIOX_WS_ENDPOINT,
) : LiveTranscriber {
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private val finals = StringBuilder()   // touched only on OkHttp's reader thread
    @Volatile private var finalSnapshot = ""
    private var lastPosted: Pair<String, String>? = null
    private val done = CompletableDeferred<String>()

    override fun start() {
        val request = Request.Builder().url(endpoint).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Stream failed (${response?.code})", t)
                done.completeExceptionally(t)
            }

            // Soniox closes the socket itself after sending the finished response.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.completeExceptionally(IOException("Soniox closed before transcription completion (code $code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.completeExceptionally(IOException("Soniox closed before transcription completion (code $code)"))
            }
        })
        ws = socket
        // OkHttp queues outgoing messages until the handshake completes, in order, so
        // enqueuing the config here guarantees it precedes any audio frame sent before open.
        socket.send(config().toString())
    }

    private fun config(): JSONObject {
        val cfg = JSONObject()
            .put("api_key", apiKey)
            .put("model", SONIOX_RT_MODEL)
            .put("audio_format", "pcm_s16le")
            .put("sample_rate", 16000)
            .put("num_channels", 1)
            // Finalize tokens as soon as the speaker pauses, so text locks in while dictating.
            .put("enable_endpoint_detection", true)
        if (language != "auto") cfg.put("language_hints", JSONArray().put(language))
        return cfg
    }

    /** Sends [len] bytes of PCM. Copies the buffer, so the caller may reuse it. */
    override fun send(pcm: ByteArray, len: Int) {
        if (len <= 0) return  // an empty frame would signal end of audio
        ws?.send(pcm.toByteString(0, len))
    }

    /**
     * Tells Soniox no more audio is coming (an empty text message, per its docs), waits for it to flush the
     * last tokens and report `finished`, and returns the full finalized transcript. Throws
     * if the stream had failed (bad key, error response, connection drop).
     */
    override suspend fun finish(): String {
        ws?.send("")
        return withTimeoutOrNull(8_000) { done.await() } ?: run {
            Log.w(TAG, "Timed out waiting for Soniox to finish; falling back to recorded audio")
            ws?.cancel()
            throw IOException("Timed out waiting for transcription completion")
        }
    }

    /** Drops the connection immediately, discarding any pending results. */
    override fun cancel() {
        done.complete(finalSnapshot)
        ws?.cancel()
    }

    private fun handle(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        if (json.has("error_code")) {
            val msg = "Soniox error ${json.optInt("error_code")}: ${json.optString("error_message")}"
            Log.w(TAG, msg)
            done.completeExceptionally(IOException(msg))
            ws?.cancel()
            return
        }
        val tokens = json.optJSONArray("tokens") ?: JSONArray()
        val interim = StringBuilder()
        for (i in 0 until tokens.length()) {
            val tok = tokens.optJSONObject(i) ?: continue
            val t = tok.optString("text")
            if (t.isEmpty() || t in CONTROL_TOKENS) continue
            if (tok.optBoolean("is_final")) finals.append(t) else interim.append(t)
        }
        finalSnapshot = finals.toString().trim()
        val update = finalSnapshot to interim.toString().trim()
        if (update != lastPosted) {
            lastPosted = update
            main.post { onTranscript(update.first, update.second) }
        }
        if (json.optBoolean("finished")) {
            done.complete(finalSnapshot)
            ws?.close(1000, null)
        }
    }
}
