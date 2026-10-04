package com.example.smartnotetaker

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live integration tests against the real provider APIs, run on the connected device.
 *
 * Keys arrive as instrumentation arguments (app/build.gradle.kts reads them from the repo's
 * untracked .env, see .env.example). A provider whose key is absent is skipped via [assumeTrue],
 * so the suite passes on a machine with no keys at all. Key values are never logged.
 *
 * The audio is whisper.cpp's jfk.wav (16 kHz mono 16-bit PCM, ~11 s): "...ask not what your
 * country can do for you, ask what you can do for your country."
 */
@RunWith(AndroidJUnit4::class)
class LiveProviderTest {

    private companion object {
        const val TAG = "LiveProviderTest"
        const val TEST_TIMEOUT_MS = 60_000L
        const val CANCEL_FINISH_LIMIT_MS = 3_000L
        /** 100 ms of 16 kHz mono 16-bit PCM, the size the keyboard's recorder hands out. */
        const val CHUNK_BYTES = 3_200
        /** Pause between chunks so the feed resembles live capture (~1.25x real time). */
        const val CHUNK_PACING_MS = 80L
        const val WAV_HEADER_BYTES = 44

        /** One client for the whole suite, as the keyboard service does. */
        val client = OkHttpClient()

        val instrumentation get() = InstrumentationRegistry.getInstrumentation()

        /** Instrumentation argument for [name], or "" when the key was not supplied. */
        fun key(name: String): String = InstrumentationRegistry.getArguments().getString(name)?.trim() ?: ""

        fun requireKey(name: String): String {
            val k = key(name)
            assumeTrue("$name not set in .env; skipping", k.isNotBlank())
            return k
        }

        /** jfk.wav PCM payload (header stripped), loaded once from the test APK's assets. */
        val jfkPcm: ByteArray by lazy {
            val bytes = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
            bytes.copyOfRange(WAV_HEADER_BYTES, bytes.size)
        }
    }

    // ----- Helpers -------------------------------------------------------------------------

    /** App context whose SharedPreferences are namespaced so tests do not touch the app's own usage counters. */
    private val appContext: Context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("livetest_$name", mode)
    }

    private fun usageTracker() = UsageTracker(appContext)

    private fun allKeys() = ApiKeys(
        openai = key("OPENAI_API_KEY"),
        deepgram = key("DEEPGRAM_API_KEY"),
        groq = key("GROQ_API_KEY"),
        elevenLabs = key("ELEVENLABS_API_KEY"),
        assemblyAi = key("ASSEMBLYAI_API_KEY"),
        soniox = key("SONIOX_API_KEY"),
    )

    /** Writes a fresh copy of jfk.wav into the app's cache dir for one-shot uploads. */
    private fun tempJfkWav(): File {
        val f = File.createTempFile("jfk-", ".wav", appContext.cacheDir)
        instrumentation.context.assets.open("jfk.wav").use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun assertJfk(provider: String, mode: String, transcript: String) {
        val lower = transcript.lowercase()
        assertTrue("[$provider $mode] transcript should contain \"ask not\": \"$transcript\"", "ask not" in lower)
        assertTrue("[$provider $mode] transcript should contain \"country\": \"$transcript\"", "country" in lower)
    }

    private fun <T> live(block: suspend () -> T): T = runBlocking { withTimeout(TEST_TIMEOUT_MS) { block() } }

    /** Callbacks are posted to the main looper; run an empty main-thread task to drain what was queued so far. */
    private fun drainMainLooper() = instrumentation.runOnMainSync { }

    /** Feeds jfk.wav through [stream] as paced 100 ms chunks and returns (transcript, finish latency ms). */
    private suspend fun streamJfk(stream: LiveTranscriber): Pair<String, Long> {
        stream.start()
        var offset = 0
        while (offset < jfkPcm.size) {
            val len = minOf(CHUNK_BYTES, jfkPcm.size - offset)
            stream.send(jfkPcm.copyOfRange(offset, offset + len), len)
            offset += len
            delay(CHUNK_PACING_MS)
        }
        val t0 = System.currentTimeMillis()
        val transcript = stream.finish()
        return transcript to (System.currentTimeMillis() - t0)
    }

    private fun runStreamingTest(provider: String, keyName: String, factory: (String, (String, String) -> Unit) -> LiveTranscriber) {
        val apiKey = requireKey(keyName)
        val nonEmptyCallbacks = AtomicInteger()
        val callbacks = AtomicInteger()
        val stream = factory(apiKey) { finalText, interim ->
            callbacks.incrementAndGet()
            if (finalText.isNotBlank() || interim.isNotBlank()) nonEmptyCallbacks.incrementAndGet()
        }
        val (transcript, latencyMs) = live { streamJfk(stream) }
        drainMainLooper()
        Log.i(TAG, "[$provider streaming] finish() latency=${latencyMs}ms callbacks=${callbacks.get()} " +
            "(non-empty=${nonEmptyCallbacks.get()}) transcript=\"$transcript\"")
        assertTrue(
            "[$provider streaming] onTranscript never delivered non-empty text before finish() returned " +
                "(callbacks=${callbacks.get()})",
            nonEmptyCallbacks.get() > 0,
        )
        assertJfk(provider, "streaming", transcript)
    }

    private fun runOneShotTest(provider: String, keyName: String) {
        requireKey(keyName)
        val wav = tempJfkWav()
        try {
            val t0 = System.currentTimeMillis()
            val transcript = live {
                AIProcessor().transcribe(
                    choice = provider,
                    language = "en",
                    audioFile = wav,
                    wavRecorder = WavRecorder(appContext),
                    modelDownloader = LocalModelDownloader(appContext),
                    keys = allKeys(),
                    usageTracker = usageTracker(),
                    onStatus = { Log.d(TAG, "[$provider one-shot] status: $it") },
                    onProgress = {},
                )
            }
            val latencyMs = System.currentTimeMillis() - t0
            Log.i(TAG, "[$provider one-shot] latency=${latencyMs}ms transcript=\"$transcript\"")
            assertJfk(provider, "one-shot", transcript)
        } finally {
            wav.delete()
        }
    }

    private fun runCancelTest(provider: String, keyName: String, factory: (String) -> LiveTranscriber) {
        val apiKey = requireKey(keyName)
        val stream = factory(apiKey)
        val elapsedMs = live {
            stream.start()
            stream.send(jfkPcm.copyOf(CHUNK_BYTES), CHUNK_BYTES)
            delay(200)  // let the socket open (or fail) before pulling the plug
            stream.cancel()
            val t0 = System.currentTimeMillis()
            // After cancel() any outcome is fine (text so far, "", or an exception); only promptness matters.
            val outcome = runCatching { stream.finish() }
            val elapsed = System.currentTimeMillis() - t0
            Log.i(TAG, "[$provider cancel] finish() after cancel() returned in ${elapsed}ms: " +
                outcome.fold({ "\"$it\"" }, { "threw ${it.javaClass.simpleName}" }))
            elapsed
        }
        assertTrue("[$provider cancel] finish() after cancel() took ${elapsedMs}ms (limit ${CANCEL_FINISH_LIMIT_MS}ms)",
            elapsedMs < CANCEL_FINISH_LIMIT_MS)
    }

    // ----- a. Streaming ---------------------------------------------------------------------

    @Test fun streaming_deepgram() = runStreamingTest(PROVIDER_DEEPGRAM, "DEEPGRAM_API_KEY") { k, cb ->
        DeepgramStream(client, k, "en", cb)
    }

    @Test fun streaming_openai() = runStreamingTest(PROVIDER_OPENAI, "OPENAI_API_KEY") { k, cb ->
        OpenAiStream(client, k, "en", cb)
    }

    @Test fun streaming_elevenlabs() = runStreamingTest(PROVIDER_ELEVENLABS, "ELEVENLABS_API_KEY") { k, cb ->
        ElevenLabsStream(client, k, "en", cb)
    }

    @Test fun streaming_assemblyai() = runStreamingTest(PROVIDER_ASSEMBLYAI, "ASSEMBLYAI_API_KEY") { k, cb ->
        AssemblyAiStream(client, k, "en", cb)
    }

    // ----- b. One-shot REST -----------------------------------------------------------------

    @Test fun oneShot_openai() = runOneShotTest(PROVIDER_OPENAI, "OPENAI_API_KEY")
    @Test fun oneShot_deepgram() = runOneShotTest(PROVIDER_DEEPGRAM, "DEEPGRAM_API_KEY")
    @Test fun oneShot_elevenlabs() = runOneShotTest(PROVIDER_ELEVENLABS, "ELEVENLABS_API_KEY")
    @Test fun oneShot_assemblyai() = runOneShotTest(PROVIDER_ASSEMBLYAI, "ASSEMBLYAI_API_KEY")

    // ----- c. LLM cleanup / modify ----------------------------------------------------------

    @Test fun llm_cleanText_removesFillers() {
        val apiKey = requireKey("OPENAI_API_KEY")
        val t0 = System.currentTimeMillis()
        val cleaned = live {
            AIProcessor().cleanText("um so ask not uh what your country can do for you", apiKey, usageTracker())
        }
        Log.i(TAG, "[LLM cleanText] latency=${System.currentTimeMillis() - t0}ms result=\"$cleaned\"")
        assertTrue("[LLM cleanText] returned blank text", cleaned.isNotBlank())
        val words = cleaned.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        assertFalse("[LLM cleanText] filler \"um\" survived: \"$cleaned\"", "um" in words)
        assertFalse("[LLM cleanText] filler \"uh\" survived: \"$cleaned\"", "uh" in words)
    }

    @Test fun llm_modifyText_uppercases() {
        val apiKey = requireKey("OPENAI_API_KEY")
        val t0 = System.currentTimeMillis()
        val modified = live {
            AIProcessor().modifyText("hello world", "make it uppercase", apiKey, usageTracker())
        }
        Log.i(TAG, "[LLM modifyText] latency=${System.currentTimeMillis() - t0}ms result=\"$modified\"")
        assertTrue("[LLM modifyText] expected \"HELLO\" in: \"$modified\"", "HELLO" in modified)
    }

    // ----- d. Cancel ------------------------------------------------------------------------

    @Test fun cancel_deepgram() = runCancelTest(PROVIDER_DEEPGRAM, "DEEPGRAM_API_KEY") { k ->
        DeepgramStream(client, k, "en", onTranscript = { _, _ -> })
    }

    @Test fun cancel_openai() = runCancelTest(PROVIDER_OPENAI, "OPENAI_API_KEY") { k ->
        OpenAiStream(client, k, "en", onTranscript = { _, _ -> })
    }

    @Test fun cancel_elevenlabs() = runCancelTest(PROVIDER_ELEVENLABS, "ELEVENLABS_API_KEY") { k ->
        ElevenLabsStream(client, k, "en", onTranscript = { _, _ -> })
    }

    @Test fun cancel_assemblyai() = runCancelTest(PROVIDER_ASSEMBLYAI, "ASSEMBLYAI_API_KEY") { k ->
        AssemblyAiStream(client, k, "en", onTranscript = { _, _ -> })
    }
}
