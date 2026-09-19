package com.example.smartnotetaker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class AssemblyAiStreamTest {
    private lateinit var server: FakeWsServer
    private val recorder = TranscriptRecorder()

    @Before fun setUp() { server = FakeWsServer() }
    @After fun tearDown() { server.close() }

    private fun stream(language: String = "en") =
        AssemblyAiStream(testClient(), "aai-test", language, recorder.callback, "${server.wsUrl}/v3/ws")

    private fun turn(transcript: String, endOfTurn: Boolean, formatted: Boolean, order: Int) = JSONObject()
        .put("type", "Turn").put("transcript", transcript)
        .put("end_of_turn", endOfTurn).put("turn_is_formatted", formatted).put("turn_order", order)
        .toString()

    @Test
    fun `english uses the english model with pcm params and a bare API key`() {
        server.expectUpgrade()
        stream("en").start()
        val req = server.awaitOpen()
        val url = req.requestUrl!!
        assertEquals("/v3/ws", url.encodedPath)
        assertEquals("16000", url.queryParameter("sample_rate"))
        assertEquals("pcm_s16le", url.queryParameter("encoding"))
        assertEquals("true", url.queryParameter("format_turns"))
        assertEquals("universal-streaming-english", url.queryParameter("speech_model"))
        assertNull(url.queryParameter("language_codes"))
        assertEquals("aai-test", req.getHeader("Authorization"))
    }

    @Test
    fun `supported non-english language steers the multilingual model`() {
        server.expectUpgrade()
        stream("es").start()
        val url = server.awaitOpen().requestUrl!!
        assertEquals("universal-streaming-multilingual", url.queryParameter("speech_model"))
        assertEquals("""["es"]""", url.queryParameter("language_codes"))
    }

    @Test
    fun `auto and unsupported languages fall back to multilingual auto-detect`() {
        server.expectUpgrade()
        stream("ja").start()
        val url = server.awaitOpen().requestUrl!!
        assertEquals("universal-streaming-multilingual", url.queryParameter("speech_model"))
        assertNull(url.queryParameter("language_codes"))
    }

    @Test
    fun `small buffers are coalesced into frames of at least 50 ms`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        val a = pcm(500)  // 1000 bytes, under the 1600-byte minimum
        s.send(a, a.size)
        assertTrue("no frame expected under 1600 bytes", server.noFrame())
        s.send(a, a.size)
        val frame = server.nextBinary()
        assertEquals(2000, frame.size)
        assertEquals(a.toList() + a.toList(), frame.toByteArray().toList())
    }

    @Test
    fun `partials, unformatted and formatted finals are reported correctly`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(turn("hello", false, false, 0))
        server.send(turn("hello world", true, false, 0))   // unformatted final: still interim
        server.send(turn("Hello world.", true, true, 0))   // formatted twin: locked in
        server.send(turn("bye", false, false, 1))
        server.send(turn("Bye.", true, true, 1))
        assertEquals(
            listOf("" to "hello", "" to "hello world", "Hello world." to "", "Hello world." to "bye", "Hello world. Bye." to ""),
            recorder.await(5),
        )
    }

    @Test
    fun `finish pads and flushes pending audio, sends Terminate, returns on Termination`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        val a = pcm(100)  // 200 bytes left coalescing
        s.send(a, a.size)
        server.send(turn("Ask not.", true, true, 0))
        recorder.await(1)

        val finishing = async(Dispatchers.IO) { s.finish() }
        val flushed = server.nextBinary()
        assertEquals(1600, flushed.size)  // zero-padded to the 50 ms minimum
        assertEquals(a.toList(), flushed.toByteArray().take(200))
        assertEquals("""{"type":"Terminate"}""", server.nextText())
        server.send("""{"type":"Termination","audio_duration_seconds":1,"session_duration_seconds":2}""")
        assertEquals("Ask not.", finishing.await())
    }

    @Test
    fun `an unformatted final with no formatted twin is kept at termination`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(turn("First.", true, true, 0))
        server.send(turn("second part", true, false, 1))
        recorder.await(2)
        val finishing = async(Dispatchers.IO) { s.finish() }
        server.nextText()  // Terminate
        server.send("""{"type":"Termination"}""")
        assertEquals("First. second part", finishing.await())
    }

    @Test
    fun `an error message fails the stream`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send("""{"error":"Not authorized"}""")
        val failed = try { kotlinx.coroutines.withContext(Dispatchers.IO) { s.finish() }; false } catch (e: Exception) { true }
        assertTrue(failed)
    }
}
