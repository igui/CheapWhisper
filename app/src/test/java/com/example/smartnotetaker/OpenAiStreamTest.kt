package com.example.smartnotetaker

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
class OpenAiStreamTest {
    private lateinit var server: FakeWsServer
    private val recorder = TranscriptRecorder()

    @Before fun setUp() { server = FakeWsServer() }
    @After fun tearDown() { server.close() }

    private fun stream(language: String = "en") = OpenAiStream(
        testClient(), "sk-test", language, recorder.callback, "${server.wsUrl}/v1/realtime?intent=transcription",
    )

    private fun event(type: String, vararg fields: Pair<String, String>) =
        JSONObject().put("type", type).apply { fields.forEach { (k, v) -> put(k, v) } }.toString()

    /** Opens the socket and consumes the session.update the client sends first. */
    private fun open(s: OpenAiStream): JSONObject {
        s.start()
        val req = server.awaitOpen()
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertEquals("transcription", req.requestUrl!!.queryParameter("intent"))
        return JSONObject(server.nextText())
    }

    @Test
    fun `first frame is a transcription session update at 24 kHz with the language`() {
        server.expectUpgrade()
        val cfg = open(stream("en"))
        assertEquals("session.update", cfg.getString("type"))
        val session = cfg.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(24000, input.getJSONObject("format").getInt("rate"))
        assertEquals("gpt-4o-mini-transcribe", input.getJSONObject("transcription").getString("model"))
        assertEquals("en", input.getJSONObject("transcription").getString("language"))
        assertEquals("server_vad", input.getJSONObject("turn_detection").getString("type"))
    }

    @Test
    fun `auto language omits the language field`() {
        server.expectUpgrade()
        val cfg = open(stream("auto"))
        val transcription = cfg.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
        assertTrue(!transcription.has("language"))
    }

    @Test
    fun `send emits base64 append events upsampled from 16 kHz to 24 kHz`() {
        server.expectUpgrade()
        val s = stream()
        open(s)
        val audio = pcm(1600)  // 100 ms at 16 kHz
        s.send(audio, audio.size)
        val frame = JSONObject(server.nextText())
        assertEquals("input_audio_buffer.append", frame.getString("type"))
        val decoded = Base64.decode(frame.getString("audio"), Base64.NO_WRAP)
        // 1600 samples -> ~2400 samples (4800 bytes), ±2 samples at the boundary.
        assertTrue("got ${decoded.size} bytes", decoded.size in 4796..4804)
        assertEquals(0, decoded.size % 2)
        // A second buffer continues seamlessly: total output ≈ 1.5x total input.
        s.send(audio, audio.size)
        val decoded2 = Base64.decode(JSONObject(server.nextText()).getString("audio"), Base64.NO_WRAP)
        assertTrue("total ${decoded.size + decoded2.size}", (decoded.size + decoded2.size) in 9596..9604)
    }

    @Test
    fun `audio sent before the socket opens is flushed after the session update`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        val audio = pcm(160)
        s.send(audio, audio.size)  // likely before onOpen
        server.awaitOpen()
        assertEquals("session.update", JSONObject(server.nextText()).getString("type"))
        assertEquals("input_audio_buffer.append", JSONObject(server.nextText()).getString("type"))
    }

    @Test
    fun `deltas are interim, completed items are appended to finals`() {
        server.expectUpgrade()
        val s = stream()
        open(s)
        server.send(event("input_audio_buffer.committed", "item_id" to "item_1"))
        server.send(event("conversation.item.input_audio_transcription.delta", "item_id" to "item_1", "delta" to "Hello"))
        server.send(event("conversation.item.input_audio_transcription.delta", "item_id" to "item_1", "delta" to " there"))
        server.send(event("conversation.item.input_audio_transcription.completed", "item_id" to "item_1", "transcript" to "Hello there."))
        server.send(event("input_audio_buffer.committed", "item_id" to "item_2"))
        server.send(event("conversation.item.input_audio_transcription.completed", "item_id" to "item_2", "transcript" to "Bye."))
        val calls = recorder.await(4)
        assertEquals(listOf("" to "Hello", "" to "Hello there", "Hello there." to "", "Hello there. Bye." to ""), calls)
    }

    @Test
    fun `finish commits the tail, waits for its transcription, closes, and returns finals`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        open(s)
        server.send(event("input_audio_buffer.committed", "item_id" to "item_1"))
        server.send(event("conversation.item.input_audio_transcription.completed", "item_id" to "item_1", "transcript" to "Ask not"))
        recorder.await(1)

        val finishing = async(Dispatchers.IO) { s.finish() }
        assertEquals("""{"type":"input_audio_buffer.commit"}""", server.nextText())
        server.send(event("input_audio_buffer.committed", "item_id" to "item_2"))
        // Not done yet: item_2 is pending until its transcript arrives.
        assertTrue(finishing.isActive)
        server.send(event("conversation.item.input_audio_transcription.completed", "item_id" to "item_2", "transcript" to "what your country."))
        assertEquals("Ask not what your country.", finishing.await())
        assertTrue("client should close the socket", server.awaitClientClose())
        assertEquals(1000, server.closeCode)
    }

    @Test
    fun `finish treats a buffer-too-small error as nothing left to transcribe`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        open(s)
        server.send(event("input_audio_buffer.committed", "item_id" to "item_1"))
        server.send(event("conversation.item.input_audio_transcription.completed", "item_id" to "item_1", "transcript" to "All done."))
        recorder.await(1)
        val finishing = async(Dispatchers.IO) { s.finish() }
        server.nextText()  // the commit
        server.send("""{"type":"error","error":{"message":"Error committing input audio buffer: buffer too small."}}""")
        assertEquals("All done.", finishing.await())
    }

    @Test
    fun `a session error before finish fails the stream`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        open(s)
        server.send("""{"type":"error","error":{"message":"Invalid API key"}}""")
        val failed = try { kotlinx.coroutines.withContext(Dispatchers.IO) { s.finish() }; false } catch (e: Exception) { true }
        assertTrue("finish() should throw after a session error", failed)
    }
    @Test
    fun `quota error after final commit must fail rather than acknowledge silence`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        open(s)
        val finishing = async(Dispatchers.IO) { runCatching { s.finish() } }
        server.nextText()
        server.send("""{"type":"error","error":{"code":"insufficient_quota","message":"Quota exhausted"}}""")
        assertTrue(finishing.await().isFailure)
    }

    @Test
    fun `failed audio item must fall back rather than return other completed text`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        open(s)
        val finishing = async(Dispatchers.IO) { runCatching { s.finish() } }
        server.nextText()
        server.send(event("input_audio_buffer.committed", "item_id" to "failed-item"))
        server.send(event("conversation.item.input_audio_transcription.failed", "item_id" to "failed-item"))
        assertTrue(finishing.await().exceptionOrNull() is java.io.IOException)
    }

}
