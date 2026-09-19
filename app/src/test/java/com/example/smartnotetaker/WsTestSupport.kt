package com.example.smartnotetaker

import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A fake provider WebSocket server: one MockWebServer that upgrades the next request to a
 * WebSocket, records every frame the client sends, and lets the test push scripted messages
 * back through [socket].
 */
class FakeWsServer : AutoCloseable {
    val server = MockWebServer()
    private val frames = LinkedBlockingQueue<Any>()   // String (text) or ByteString (binary)
    private val opened = CountDownLatch(1)
    private val closing = CountDownLatch(1)
    @Volatile lateinit var socket: WebSocket
        private set
    @Volatile var closeCode: Int = -1
        private set

    init {
        server.start()
    }

    /** ws:// base URL of this server (no trailing slash). */
    val wsUrl: String get() = "ws://${server.hostName}:${server.port}"

    /** Queues a WebSocket upgrade for the next request. */
    fun expectUpgrade() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                opened.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.add(bytes) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(1000, null)
                closing.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closing.countDown()
            }
        }))
    }

    /** Queues a plain HTTP failure (no upgrade), e.g. 401 for a bad key. */
    fun expectRejection(code: Int = 401) {
        server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"unauthorized"}"""))
    }

    fun awaitOpen(): RecordedRequest {
        if (!opened.await(5, TimeUnit.SECONDS)) fail("client never connected")
        return server.takeRequest(1, TimeUnit.SECONDS) ?: error("no recorded request")
    }

    /** True once the client sent a close frame (or the connection dropped). */
    fun awaitClientClose(seconds: Long = 5): Boolean = closing.await(seconds, TimeUnit.SECONDS)

    fun nextText(seconds: Long = 5): String = when (val f = frames.poll(seconds, TimeUnit.SECONDS)) {
        is String -> f
        null -> error("no text frame received within ${seconds}s")
        else -> error("expected a text frame, got binary of ${(f as ByteString).size} bytes")
    }

    fun nextBinary(seconds: Long = 5): ByteString = when (val f = frames.poll(seconds, TimeUnit.SECONDS)) {
        is ByteString -> f
        null -> error("no binary frame received within ${seconds}s")
        else -> error("expected a binary frame, got text: $f")
    }

    fun noFrame(millis: Long = 300): Boolean = frames.poll(millis, TimeUnit.MILLISECONDS) == null

    fun send(text: String) { socket.send(text) }

    override fun close() { server.shutdown() }
}

/** Records (finalText, interim) callbacks; the stream posts them to the main Looper. */
class TranscriptRecorder {
    val calls = mutableListOf<Pair<String, String>>()
    val callback: (String, String) -> Unit = { f, i -> calls.add(f to i) }

    /** Drains the main Looper until at least [n] callbacks were delivered (or 3 s pass). */
    fun await(n: Int): List<Pair<String, String>> {
        val deadline = System.currentTimeMillis() + 3_000
        while (calls.size < n && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        if (calls.size < n) fail("expected $n transcript callbacks, got ${calls.size}: $calls")
        return calls.toList()
    }

    fun drain(): List<Pair<String, String>> {
        shadowOf(Looper.getMainLooper()).idle()
        return calls.toList()
    }
}

fun testClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/** 16-bit little-endian PCM of [samples] shorts (a saw wave, so bytes are all distinct). */
fun pcm(samples: Int): ByteArray {
    val out = ByteArray(samples * 2)
    for (i in 0 until samples) {
        val v = ((i * 37) % 65536) - 32768
        out[2 * i] = (v and 0xFF).toByte()
        out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}
