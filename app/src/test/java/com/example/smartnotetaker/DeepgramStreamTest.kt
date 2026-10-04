package com.example.smartnotetaker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class DeepgramStreamTest {
    private lateinit var server: FakeWsServer
    private val recorder = TranscriptRecorder()

    @Before fun setUp() { server = FakeWsServer() }
    @After fun tearDown() { server.close() }

    private fun stream(language: String = "auto") =
        DeepgramStream(testClient(), "dg-key", language, recorder.callback, "${server.wsUrl}/v1/listen")

    private fun results(transcript: String, isFinal: Boolean) = JSONObject()
        .put("type", "Results")
        .put("is_final", isFinal)
        .put("channel", JSONObject().put("alternatives", org.json.JSONArray().put(JSONObject().put("transcript", transcript))))
        .toString()

    @Test
    fun `connects with nova-3 params and Token auth, auto maps to multi`() {
        server.expectUpgrade()
        stream("auto").start()
        val req = server.awaitOpen()
        assertEquals("/v1/listen", req.requestUrl!!.encodedPath)
        val q = req.requestUrl!!
        assertEquals("nova-3", q.queryParameter("model"))
        assertEquals("multi", q.queryParameter("language"))
        assertEquals("linear16", q.queryParameter("encoding"))
        assertEquals("16000", q.queryParameter("sample_rate"))
        assertEquals("1", q.queryParameter("channels"))
        assertEquals("true", q.queryParameter("interim_results"))
        assertEquals("true", q.queryParameter("smart_format"))
        assertEquals("Token dg-key", req.getHeader("Authorization"))
    }

    @Test
    fun `explicit language is passed through`() {
        server.expectUpgrade()
        stream("es").start()
        assertEquals("es", server.awaitOpen().requestUrl!!.queryParameter("language"))
    }

    @Test
    fun `send forwards raw PCM bytes as one binary frame`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        val audio = pcm(800)
        s.send(audio, audio.size)
        assertEquals(audio.toList(), server.nextBinary().toByteArray().toList())
    }

    @Test
    fun `interim and final results are reported in order and finals are space-joined`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(results("hello", false))
        server.send(results("hello world", true))
        server.send(results("how", false))
        server.send(results("how are you", true))
        val calls = recorder.await(4)
        assertEquals(listOf("" to "hello", "hello world" to "", "hello world" to "how", "hello world how are you" to ""), calls)
        // Empty finals (silence) neither add spaces nor fire spurious joins.
        server.send(results("", true))
        assertEquals("hello world how are you" to "", recorder.await(5).last())
    }

    @Test
    fun `finish sends CloseStream, waits for the server close, and returns the joined finals`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(results("ask not", true))
        server.send(results("what your country", true))
        recorder.await(2)

        val finishing = async(Dispatchers.IO) { s.finish() }
        assertEquals("""{"type":"CloseStream"}""", server.nextText())
        // Deepgram flushes, sends Metadata, then closes the socket itself.
        server.send("""{"type":"Metadata","request_id":"x","duration":1.2}""")
        server.socket.close(1000, "done")
        assertEquals("ask not what your country", finishing.await())
    }

    @Test
    fun `finish throws on timeout even when partial finals exist`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(results("partial answer", true))
        recorder.await(1)
        val t0 = System.currentTimeMillis()
        val error = runCatching { withContext(Dispatchers.IO) { s.finish() } }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("Incomplete streaming must fall back to the WAV", error is java.io.IOException)
        assertTrue("timed out after ${elapsed}ms", elapsed in 7_000..12_000)
    }
}
