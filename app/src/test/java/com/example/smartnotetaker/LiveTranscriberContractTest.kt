package com.example.smartnotetaker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.After
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
}
