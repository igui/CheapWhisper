package com.example.smartnotetaker

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ElevenLabsStreamTest {
    private lateinit var server: FakeWsServer
    private val recorder = TranscriptRecorder()

    @Before fun setUp() { server = FakeWsServer() }
    @After fun tearDown() { server.close() }

    private fun stream(language: String = "auto") = ElevenLabsStream(
        testClient(), "xi-test", language, recorder.callback, "${server.wsUrl}/v1/speech-to-text/realtime",
    )

    private fun msg(type: String, text: String) =
        JSONObject().put("message_type", type).put("text", text).toString()

    @Test
    fun `connects with scribe realtime params and xi-api-key, auto omits language_code`() {
        server.expectUpgrade()
        stream("auto").start()
        val req = server.awaitOpen()
        val url = req.requestUrl!!
        assertEquals("/v1/speech-to-text/realtime", url.encodedPath)
        assertEquals("scribe_v2_realtime", url.queryParameter("model_id"))
        assertEquals("pcm_16000", url.queryParameter("audio_format"))
        assertEquals("vad", url.queryParameter("commit_strategy"))
        assertNull(url.queryParameter("language_code"))
        assertEquals("xi-test", req.getHeader("xi-api-key"))
    }

    @Test
    fun `explicit language sets language_code`() {
        server.expectUpgrade()
        stream("fr").start()
        assertEquals("fr", server.awaitOpen().requestUrl!!.queryParameter("language_code"))
    }

    @Test
    fun `send wraps the PCM as a base64 input_audio_chunk`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        val audio = pcm(640)
        s.send(audio, audio.size)
        val frame = JSONObject(server.nextText())
        assertEquals("input_audio_chunk", frame.getString("message_type"))
        assertFalse(frame.getBoolean("commit"))
        assertEquals(16000, frame.getInt("sample_rate"))
        assertEquals(audio.toList(), Base64.decode(frame.getString("audio_base_64"), Base64.NO_WRAP).toList())
    }

    @Test
    fun `send honours the len argument`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        val audio = pcm(100)
        s.send(audio, 50)
        val decoded = Base64.decode(JSONObject(server.nextText()).getString("audio_base_64"), Base64.NO_WRAP)
        assertEquals(audio.take(50), decoded.toList())
    }

    @Test
    fun `partials are interim and committed segments are space-joined finals`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(msg("partial_transcript", "hel"))
        server.send(msg("partial_transcript", "hello"))
        server.send(msg("committed_transcript", "hello"))
        server.send(msg("partial_transcript", "wor"))
        server.send(msg("committed_transcript", "world"))
        assertEquals(
            listOf("" to "hel", "" to "hello", "hello" to "", "hello" to "wor", "hello world" to ""),
            recorder.await(5),
        )
    }

    @Test
    fun `finish sends a manual commit, waits for the last committed transcript, then closes`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(msg("committed_transcript", "ask not"))
        recorder.await(1)

        val finishing = async(Dispatchers.IO) { s.finish() }
        val commit = JSONObject(server.nextText())
        assertEquals("input_audio_chunk", commit.getString("message_type"))
        assertTrue(commit.getBoolean("commit"))
        assertEquals("", commit.getString("audio_base_64"))
        server.send(msg("committed_transcript", "what your country"))
        assertEquals("ask not what your country", finishing.await())
        assertTrue(server.awaitClientClose())
    }

    @Test
    fun `finish completes on insufficient_audio_activity when nothing was pending`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send(msg("committed_transcript", "all there"))
        recorder.await(1)
        val finishing = async(Dispatchers.IO) { s.finish() }
        server.nextText()
        server.send("""{"message_type":"insufficient_audio_activity"}""")
        assertEquals("all there", finishing.await())
    }

    @Test
    fun `a server error message fails the stream`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.send("""{"message_type":"auth_error","error":"bad key"}""")
        val failed = try { kotlinx.coroutines.withContext(Dispatchers.IO) { s.finish() }; false } catch (e: Exception) { true }
        assertTrue(failed)
    }
}
