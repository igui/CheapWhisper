package com.example.smartnotetaker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class SonioxStreamTest {
    private lateinit var server: FakeWsServer
    private val recorder = TranscriptRecorder()

    @Before fun setUp() { server = FakeWsServer() }
    @After fun tearDown() { server.close() }

    private fun stream(language: String = "auto") =
        SonioxStream(testClient(), "sx-key", language, recorder.callback, "${server.wsUrl}/transcribe-websocket")

    /** A Soniox response: tokens as (text, is_final) pairs, optionally the finished marker. */
    private fun tokens(vararg toks: Pair<String, Boolean>, finished: Boolean = false): String {
        val arr = JSONArray()
        toks.forEach { (t, f) -> arr.put(JSONObject().put("text", t).put("is_final", f).put("start_ms", 0).put("end_ms", 10)) }
        val json = JSONObject().put("tokens", arr).put("final_audio_proc_ms", 10).put("total_audio_proc_ms", 20)
        if (finished) json.put("finished", true)
        return json.toString()
    }

    /** Opens the stream and consumes the config frame (always the first text frame). */
    private fun open(language: String = "auto"): Pair<SonioxStream, JSONObject> {
        server.expectUpgrade()
        val s = stream(language)
        s.start()
        server.awaitOpen()
        return s to JSONObject(server.nextText())
    }

    @Test
    fun `first message is the config with api_key, pcm_s16le 16k mono, no auth header, no hints for auto`() {
        server.expectUpgrade()
        stream("auto").start()
        val req = server.awaitOpen()
        assertEquals("/transcribe-websocket", req.requestUrl!!.encodedPath)
        assertNull("api_key travels in the config message, not a header", req.getHeader("Authorization"))
        val cfg = JSONObject(server.nextText())
        assertEquals("sx-key", cfg.getString("api_key"))
        assertEquals("stt-rt-v5", cfg.getString("model"))
        assertEquals("pcm_s16le", cfg.getString("audio_format"))
        assertEquals(16000, cfg.getInt("sample_rate"))
        assertEquals(1, cfg.getInt("num_channels"))
        assertTrue(cfg.getBoolean("enable_endpoint_detection"))
        assertFalse(cfg.has("language_hints"))
    }

    @Test
    fun `explicit language becomes a single language hint`() {
        val (_, cfg) = open("es")
        assertEquals(listOf("es"), List(cfg.getJSONArray("language_hints").length()) { cfg.getJSONArray("language_hints").getString(it) })
    }

    @Test
    fun `send forwards raw PCM bytes as one binary frame after the config`() {
        val (s, _) = open()
        val audio = pcm(800)
        s.send(audio, audio.size)
        assertEquals(audio.toList(), server.nextBinary().toByteArray().toList())
    }

    @Test
    fun `audio queued before the handshake still follows the config frame`() {
        server.expectUpgrade()
        val s = stream()
        s.start()
        val audio = pcm(100)
        s.send(audio, audio.size)  // before onOpen
        server.awaitOpen()
        JSONObject(server.nextText())  // config first
        assertEquals(audio.toList(), server.nextBinary().toByteArray().toList())
    }

    @Test
    fun `send ignores empty buffers so they cannot be mistaken for end of audio`() {
        val (s, _) = open()
        s.send(ByteArray(0), 0)
        assertTrue(server.noFrame())
    }

    @Test
    fun `final tokens accumulate as-is, non-final tokens replace the interim, control tokens are dropped`() {
        val (_, _) = open()
        server.send(tokens("Hel" to false, "lo" to false))
        server.send(tokens("Hello" to true, " wor" to false))
        server.send(tokens(" world" to true, "<end>" to true))
        server.send(tokens(" how" to false))
        server.send(tokens())  // no pending non-final tokens any more: interim clears
        val calls = recorder.await(5)
        assertEquals(
            listOf("" to "Hello", "Hello" to "wor", "Hello world" to "", "Hello world" to "how", "Hello world" to ""),
            calls,
        )
        // Identical state is not re-posted (Soniox streams responses continuously).
        server.send(tokens())
        Thread.sleep(200)
        assertEquals(5, recorder.drain().size)
    }

    @Test
    fun `finish sends an empty text frame, waits for finished, closes, and returns the finals`() = runBlocking {
        val (s, _) = open()
        server.send(tokens("Ask" to true, " not" to true))
        recorder.await(1)

        val finishing = async(Dispatchers.IO) { s.finish() }
        assertEquals("", server.nextText())
        // Soniox flushes the pending tokens, then reports finished and closes.
        server.send(tokens(" what" to true, " your" to false))
        server.send(tokens(" your country" to true, "<fin>" to true, finished = true))
        assertEquals("Ask not what your country", finishing.await())
        assertTrue("client should close after finished", server.awaitClientClose())
    }

    @Test
    fun `finish returns the finals when the server closes without a finished message`() = runBlocking {
        val (s, _) = open()
        server.send(tokens("Hi" to true))
        recorder.await(1)
        val finishing = async(Dispatchers.IO) { s.finish() }
        server.nextText()
        server.socket.close(1000, "done")
        assertEquals("Hi", finishing.await())
    }

    @Test
    fun `finish times out gracefully and returns the text so far`() = runBlocking {
        val (s, _) = open()
        server.send(tokens("partial" to true, " answer" to true))
        recorder.await(1)
        val t0 = System.currentTimeMillis()
        val text = withContext(Dispatchers.IO) { s.finish() }  // server never finishes
        val elapsed = System.currentTimeMillis() - t0
        assertEquals("partial answer", text)
        assertTrue("timed out after ${elapsed}ms", elapsed in 7_000..12_000)
    }

    @Test
    fun `error response makes finish throw`() = runBlocking {
        val (s, _) = open()
        server.send("""{"error_code":401,"error_type":"unauthenticated","error_message":"Incorrect API key"}""")
        try {
            withContext(Dispatchers.IO) { s.finish() }
            fail("finish() should throw on an error response")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("401") && e.message!!.contains("Incorrect API key"))
        }
    }

    @Test
    fun `finish throws when the server rejects the upgrade`() = runBlocking {
        server.expectRejection(401)
        val s = stream()
        s.start()
        try {
            withContext(Dispatchers.IO) { s.finish() }
            fail("finish() should throw on a 401")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            // expected: the IME falls back to the one-shot request
        }
    }

    @Test
    fun `cancel is idempotent and finish returns promptly afterwards`() = runBlocking {
        val (s, _) = open()
        server.send(tokens("kept" to true, " guess" to false))
        recorder.await(1)
        s.cancel()
        s.cancel()
        val t0 = System.currentTimeMillis()
        val text = withContext(Dispatchers.IO) { s.finish() }
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
        assertEquals("kept", text)
        assertTrue(server.awaitClientClose())
    }
}
