package com.example.smartnotetaker

import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hits the REAL provider APIs from the JVM (no device). Keys come from the root `.env` via
 * Gradle (`testOptions.unitTests.all { systemProperty(...) }`); a test whose key is missing
 * is skipped. Spends a few cents per run:
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*LiveProvidersJvmTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveProvidersJvmTest {
    private val client = OkHttpClient()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun key(name: String): String {
        val v = System.getProperty(name) ?: ""
        assumeTrue("$name not set in .env; skipping", v.isNotBlank())
        return v
    }

    private fun keys() = ApiKeys(
        openai = System.getProperty("OPENAI_API_KEY") ?: "",
        deepgram = System.getProperty("DEEPGRAM_API_KEY") ?: "",
        groq = System.getProperty("GROQ_API_KEY") ?: "",
        elevenLabs = System.getProperty("ELEVENLABS_API_KEY") ?: "",
        assemblyAi = System.getProperty("ASSEMBLYAI_API_KEY") ?: "",
        soniox = System.getProperty("SONIOX_API_KEY") ?: "",
    )

    /** PCM payload of the bundled jfk.wav, found by walking the RIFF chunks (it has a LIST chunk). */
    private fun jfkPcm(): ByteArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("jfk.wav")!!.readBytes()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return bytes.copyOfRange(pos + 8, minOf(pos + 8 + size, bytes.size))
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in jfk.wav")
    }

    private fun jfkWavFile(): File {
        val f = File.createTempFile("jfk", ".wav")
        val pcm = jfkPcm()
        f.writeBytes(WavRecorder(context).buildWavHeader(pcm.size.toLong()) + pcm)
        return f
    }

    private fun assertJfk(provider: String, text: String) {
        val lower = text.lowercase()
        assertTrue("$provider transcript: \"$text\"", lower.contains("ask not") && lower.contains("country"))
    }

    // ---------------------------------------------------------------- streaming

    private fun runStream(provider: String, factory: (String, (String, String) -> Unit) -> LiveTranscriber, keyName: String) = runBlocking {
        val apiKey = key(keyName)
        val callbacks = mutableListOf<Pair<String, String>>()
        val stream = factory(apiKey) { f, i -> callbacks.add(f to i) }
        val pcm = jfkPcm()
        stream.start()
        val chunk = 3200  // 100 ms
        var off = 0
        while (off < pcm.size) {
            val len = minOf(chunk, pcm.size - off)
            stream.send(pcm.copyOfRange(off, off + len), len)
            off += len
            Thread.sleep(60)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val t0 = System.currentTimeMillis()
        val text = withTimeout(60_000) { withContext(Dispatchers.IO) { stream.finish() } }
        val latency = System.currentTimeMillis() - t0
        shadowOf(Looper.getMainLooper()).idle()
        Log.i("LiveJvm", "$provider streaming: finish() took ${latency}ms, ${callbacks.size} callbacks, text=\"$text\"")
        println("$provider streaming: finish()=${latency}ms callbacks=${callbacks.size} text=\"$text\"")
        assertJfk(provider, text)
        assertTrue("$provider: expected live callbacks before finish", callbacks.any { it.first.isNotBlank() || it.second.isNotBlank() })
    }

    @Test fun streaming_deepgram() = runStream("Deepgram", { k, cb -> DeepgramStream(client, k, "en", cb) }, "DEEPGRAM_API_KEY")
    @Test fun streaming_openai() = runStream("OpenAI", { k, cb -> OpenAiStream(client, k, "en", cb) }, "OPENAI_API_KEY")
    @Test fun streaming_elevenlabs() = runStream("ElevenLabs", { k, cb -> ElevenLabsStream(client, k, "en", cb) }, "ELEVENLABS_API_KEY")
    @Test fun streaming_assemblyai() = runStream("AssemblyAI", { k, cb -> AssemblyAiStream(client, k, "en", cb) }, "ASSEMBLYAI_API_KEY")
    @Test fun streaming_soniox() = runStream("Soniox", { k, cb -> SonioxStream(client, k, "en", cb) }, "SONIOX_API_KEY")

    // ---------------------------------------------------------------- one-shot

    private fun runOneShot(provider: String, keyName: String) = runBlocking {
        key(keyName)
        val file = jfkWavFile()
        val t0 = System.currentTimeMillis()
        val text = withTimeout(90_000) {
            AIProcessor().transcribe(
                choice = provider, language = "en", audioFile = file,
                wavRecorder = WavRecorder(context), modelDownloader = LocalModelDownloader(context),
                keys = keys(), usageTracker = UsageTracker(context), onStatus = { }, onProgress = { },
            )
        }
        println("$provider one-shot: ${System.currentTimeMillis() - t0}ms text=\"$text\"")
        file.delete()
        assertJfk(provider, text)
    }

    @Test fun oneShot_openai() = runOneShot(PROVIDER_OPENAI, "OPENAI_API_KEY")
    @Test fun oneShot_deepgram() = runOneShot(PROVIDER_DEEPGRAM, "DEEPGRAM_API_KEY")
    @Test fun oneShot_elevenlabs() = runOneShot(PROVIDER_ELEVENLABS, "ELEVENLABS_API_KEY")
    @Test fun oneShot_assemblyai() = runOneShot(PROVIDER_ASSEMBLYAI, "ASSEMBLYAI_API_KEY")
    @Test fun oneShot_soniox() = runOneShot(PROVIDER_SONIOX, "SONIOX_API_KEY")

    // ---------------------------------------------------------------- LLM

    @Test
    fun llm_cleanText_removesFillers() = runBlocking {
        val apiKey = key("OPENAI_API_KEY")
        val t0 = System.currentTimeMillis()
        val out = withTimeout(60_000) {
            AIProcessor().cleanText("um so ask not uh what your country can do for you", apiKey, UsageTracker(context))
        }
        println("cleanText (${OPENAI_LLM_MODEL}): ${System.currentTimeMillis() - t0}ms out=\"$out\"")
        assertTrue(out.isNotBlank())
        val words = out.lowercase().split(Regex("\\W+"))
        assertFalse("filler left in: $out", "um" in words || "uh" in words)
    }

    @Test
    fun llm_modifyText_uppercases() = runBlocking {
        val apiKey = key("OPENAI_API_KEY")
        val out = withTimeout(60_000) {
            AIProcessor().modifyText("hello world", "make it all uppercase", apiKey, UsageTracker(context))
        }
        println("modifyText: out=\"$out\"")
        assertTrue(out, out.contains("HELLO"))
    }
}
