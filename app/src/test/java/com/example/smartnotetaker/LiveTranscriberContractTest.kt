package com.example.smartnotetaker

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Behaviour every [LiveTranscriber] must share, run against each provider client. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveTranscriberContractTest(
    private val name: String,
    private val factory: (OkHttpClient, String, (String, String) -> Unit) -> LiveTranscriber,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun providers(): List<Array<Any>> = listOf(
            arrayOf("Deepgram", { c: OkHttpClient, base: String, cb: (String, String) -> Unit ->
                DeepgramStream(c, "k", "auto", cb, "$base/v1/listen") }),
            arrayOf("OpenAI", { c: OkHttpClient, base: String, cb: (String, String) -> Unit ->
                OpenAiStream(c, "k", "auto", cb, "$base/v1/realtime?intent=transcription") }),
            arrayOf("ElevenLabs", { c: OkHttpClient, base: String, cb: (String, String) -> Unit ->
                ElevenLabsStream(c, "k", "auto", cb, "$base/v1/speech-to-text/realtime") }),
            arrayOf("AssemblyAI", { c: OkHttpClient, base: String, cb: (String, String) -> Unit ->
                AssemblyAiStream(c, "k", "auto", cb, "$base/v3/ws") }),
            arrayOf("Soniox", { c: OkHttpClient, base: String, cb: (String, String) -> Unit ->
                SonioxStream(c, "k", "auto", cb, "$base/transcribe-websocket") }),
        )
    }

    private lateinit var server: FakeWsServer
    private val recorder = TranscriptRecorder()

    @Before fun setUp() { server = FakeWsServer() }
    @After fun tearDown() { server.close() }

    private fun stream() = factory(testClient(), server.wsUrl, recorder.callback)

    @Test
    fun `finish throws when the server rejects the upgrade`() = runBlocking {
        server.expectRejection(401)
        val s = stream()
        s.start()
        try {
            withContext(Dispatchers.IO) { s.finish() }
            fail("$name: finish() should throw on a 401")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            // expected: the IME falls back to the one-shot request
        }
    }

    @Test
    fun `cancel is idempotent and finish returns promptly afterwards`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        s.cancel()
        s.cancel()
        val t0 = System.currentTimeMillis()
        withContext(Dispatchers.IO) { s.finish() }
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("$name: finish() took ${elapsed}ms after cancel()", elapsed < 1_000)
    }

    @Test
    fun `finish before the socket connects does not hang`() = runBlocking {
        server.expectRejection(500)
        val s = stream()
        s.start()
        val t0 = System.currentTimeMillis()
        try { withContext(Dispatchers.IO) { s.finish() } } catch (e: Exception) { /* fine */ }
        assertTrue(System.currentTimeMillis() - t0 < 9_000)
    }
    @Test
    fun `missing completion times out as failure even with no text`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        val error = runCatching { withContext(Dispatchers.IO) { s.finish() } }.exceptionOrNull()
        assertTrue("$name: unconfirmed silence must not discard the recording", error is java.io.IOException)
    }

    @Test
    fun `close before finish is not successful silence`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        server.socket.close(1000, "session ended unexpectedly")
        assertTrue(server.awaitClientClose())
        val error = runCatching { withContext(Dispatchers.IO) { s.finish() } }.exceptionOrNull()
        assertTrue("$name: premature close must fall back to the recording", error is java.io.IOException)
    }

    @Test
    fun `unresponsive handshake cannot become a successful empty transcript`() = runBlocking {
        server.server.enqueue(okhttp3.mockwebserver.MockResponse()
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val s = stream()
        s.start()
        val error = runCatching { withContext(Dispatchers.IO) { s.finish() } }.exceptionOrNull()
        assertTrue("$name: unconnected stream must fail", error is java.io.IOException)
        s.cancel()
    }

    @Test
    fun `confirmed silent completion remains a valid empty transcript`() = runBlocking {
        server.expectUpgrade()
        val s = stream()
        s.start()
        server.awaitOpen()
        if (name == "OpenAI" || name == "Soniox") server.nextText() // session configuration
        val finishing = async(Dispatchers.IO) { s.finish() }
        server.nextText() // finish signal
        when (name) {
            "Deepgram" -> server.socket.close(1000, "done")
            "OpenAI" -> server.send("""{"type":"error","error":{"code":"input_audio_buffer_commit_empty","message":"Buffer is empty"}}""")
            "ElevenLabs" -> server.send("""{"message_type":"insufficient_audio_activity"}""")
            "AssemblyAI" -> server.send("""{"type":"Termination"}""")
            "Soniox" -> server.send("""{"finished":true}""")
        }
        assertEquals("", finishing.await())
    }

}
