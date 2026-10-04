package com.example.smartnotetaker.transcription

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.smartnotetaker.PROVIDER_OPENROUTER
import com.example.smartnotetaker.UsageTracker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedTranscriptionTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun model() = TranscriptionCatalog.resolve(TranscriptionCatalog.distinct(TranscriptionCatalog.all(context)), TranscriptionCatalog.DEFAULT)

    @Test fun modelFamiliesAreUniqueAndOldSelectionsMigrate() {
        val models = TranscriptionCatalog.distinct(TranscriptionCatalog.all(context))
        assertEquals(14, models.size)
        assertEquals(models.size, models.map { TranscriptionCatalog.family(it.id) }.distinct().size)
        assertEquals("microsoft/mai-transcribe-2", TranscriptionCatalog.resolve(models, "microsoft/mai-transcribe-1.5").id)
        assertEquals("$0.10/hour", model().price())
        assertTrue(models.none { it.id == "qwen/qwen3-asr-flash-2026-02-10" })
    }
    @Test fun compactSelectorLabelsIncludeKnownWerAndUseLanguageAuto() {
        assertTrue(model().dropdownLabel().contains("MAI-Transcribe 2"))
        assertTrue(model().dropdownLabel().contains("AA WER 2.04%"))
        val unknown = TranscriptionModel(JSONObject("""{"id":"unknown/model","name":"Unranked"}"""))
        assertFalse(unknown.dropdownLabel().contains("WER"))
        assertEquals("language: auto", mainLanguageLabel("auto"))
        assertEquals("language: Spanish", mainLanguageLabel("es"))
    }

    @Test fun liveCatalogRefreshAlsoDeduplicatesWithoutInventingUnits() {
        val first = JSONObject("""{"id":"microsoft/mai-transcribe-1.5","architecture":{"output_modalities":["transcription"]},"pricing":{"prompt":"0.36"}}""")
        val second = JSONObject(first.toString()).put("id", "microsoft/mai-transcribe-2")
        assertEquals(1, TranscriptionCatalog.merge(JSONArray().put(first).put(second), TranscriptionCatalog.all(context)).size)
    }
    @Test fun shareAcceptsAttachmentAndClipDataButNotPrivatePaths() {
        val uri = Uri.parse("content://voice.example/clip.ogg")
        assertEquals(uri, SharedRecording.uri(Intent(Intent.ACTION_SEND).setType("audio/ogg").putExtra(Intent.EXTRA_STREAM, uri)))
        assertEquals(uri, SharedRecording.uri(Intent(Intent.ACTION_SEND).setType("application/ogg").also { it.clipData = ClipData.newRawUri("Audio", uri) }))
        assertThrows(IllegalArgumentException::class.java) { SharedRecording.uri(Intent(Intent.ACTION_SEND).setType("audio/ogg").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private"))) }
        assertThrows(IllegalArgumentException::class.java) { SharedRecording.uri(Intent(Intent.ACTION_SEND).setType("text/plain")) }
    }
    @Test fun opusMimeTypesAreAcceptedByPickerAndSharing() {
        val uri = Uri.parse("content://voice.example/message.opus")
        for (mime in listOf("audio/opus", "audio/ogg", "application/opus", "application/x-opus", "application/ogg", "application/x-ogg", "application/octet-stream")) {
            assertEquals(uri, SharedRecording.uri(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)))
            assertTrue(mime.startsWith("audio/") || mime in AUDIO_PICKER_TYPES)
        }
    }

    @Test fun requestUsesTimestampArraysAndOmitsAutoLanguage() {
        val audio = File.createTempFile("unit-audio", ".wav", context.cacheDir); audio.writeBytes(byteArrayOf(1, 2))
        try {
            val request = OpenRouterApi().uploadRequest(model(), "auto", "test-key", audio)
            val form = request.body as MultipartBody
            val names = form.parts.map { it.headers!!["Content-Disposition"]!! }
            assertEquals(2, names.count { "timestamp_granularities[]" in it })
            assertTrue(names.none { "name=\"language\"" in it })
            assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", request.url.toString())
        } finally { audio.delete() }
    }
    @Test fun timestampsAreRequestedEvenForModelsPreviouslyMarkedTextOnly() {
        val marked = TranscriptionModel(JSONObject(model().metadata.toString()).put("timestampsUnavailable", true))
        val request = OpenRouterApi().uploadRequest(marked, "auto", "key", File(context.cacheDir, "unused.wav"))
        val body = request.body as MultipartBody
        assertEquals(2, body.parts.count { "timestamp_granularities[]" in it.headers!!["Content-Disposition"]!! })
    }
    @Test fun explicitTimestampRejectionFallsBackOnceAndMetersOnlySuccess() = runBlocking {
        val usage = TranscriptionUsage(context); usage.reset()
        val audio = File.createTempFile("fallback-audio", ".wav", context.cacheDir); audio.writeBytes(byteArrayOf(1,2))
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"This model does not support verbose_json timestamps"}}"""))
                server.enqueue(MockResponse().setBody("""{"text":"Hello","usage":{"seconds":2,"cost":0.001}}"""))
                val api = OpenRouterApi(OkHttpClient(), server.url("/api/v1").toString().trimEnd('/'))
                val reply = api.transcribeChunk(model(), "auto", "key", audio, 2.0, usage)
                assertTrue(AudioTranscript.parse(reply.json).cues.isEmpty())
                assertTrue(server.takeRequest().body.readUtf8().contains("verbose_json"))
                val fallback = server.takeRequest().body.readUtf8()
                assertFalse(fallback.contains("timestamp_granularities")); assertFalse(fallback.contains("verbose_json"))
                assertEquals(BigDecimal("0.001"), usage.total()); assertEquals(2, server.requestCount)
            }
        } finally { audio.delete() }
    }
    @Test fun otherBadRequestsAreNotRetried() = runBlocking {
        val audio = File.createTempFile("no-retry", ".wav", context.cacheDir)
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid language"}}"""))
                val api = OpenRouterApi(OkHttpClient(), server.url("/api/v1").toString().trimEnd('/'))
                try { api.transcribeChunk(model(), "auto", "key", audio, 2.0, TranscriptionUsage(context)); fail("Expected failure") }
                catch (_: OpenRouterApi.HttpError) { assertEquals(1, server.requestCount) }
            }
        } finally { audio.delete() }
    }

    @Test fun parsesWordsFallsBackToSegmentsAndDoesNotInventTimes() {
        val transcript = AudioTranscript.parse(JSONObject("""{"text":"Bon dia","language":"ca","words":[{"word":"Bon","start":0.1,"end":0.5},{"word":"dia","start":1,"end":2}]}"""))
        assertEquals(100, transcript.cues.first().startMs)
        assertEquals(-1, transcript.activeAt(700)); assertEquals(1, transcript.activeAt(1500))
        val segment = AudioTranscript.parse(JSONObject("""{"text":"Hello","segments":[{"text":"Hello","start":2,"end":3}]}"""))
        assertEquals(2000, segment.cues.first().startMs)
        assertTrue(AudioTranscript.parse(JSONObject("""{"text":"Plain text"}""")).cues.isEmpty())
        assertTrue(segment.srt().contains("00:00:02,000 --> 00:00:03,000"))
    }
    @Test fun missingTimingInOneChunkKeepsTheWholeTranscriptAsPlainText() {
        val combined = TranscriptAccumulator()
        combined.append(AudioTranscript("Timed part", "en", listOf(TimedCue(0, 1000, "Timed part"))), 0)
        combined.append(AudioTranscript("Untimed part", "en", emptyList()), 60000)
        assertEquals("Timed part Untimed part", combined.finish().text)
        assertTrue(combined.finish().cues.isEmpty())
    }

    @Test fun usageIsSharedWithKeyboardAndNeverRoundsEachRequestToCents() {
        val usage = TranscriptionUsage(context); usage.reset()
        repeat(3) { val id = usage.begin(model(), 10.0); usage.complete(id, JSONObject("""{"seconds":10,"cost":0.004}"""), "generation") }
        assertEquals(BigDecimal("0.012"), usage.total())
        assertTrue(usage.summary().contains("0:00:30 · $0.01"))
        assertEquals(12000, UsageTracker(context).getMicros(PROVIDER_OPENROUTER))
        assertTrue(TranscriptionUsage(context).summary(true).contains(model().name))
    }
    @Test fun unknownCostsAndCanceledRequestsStayVisible() {
        val usage = TranscriptionUsage(context); usage.reset()
        val id = usage.begin(model(), 10.0); usage.complete(id, JSONObject(), null); usage.begin(model(), 5.0)
        assertTrue(usage.summary().contains("unreported")); assertTrue(usage.summary().contains("unconfirmed"))
        assertEquals("< $0.01", dollars(BigDecimal("0.0002")))
    }
    @Test fun validationRequiresSuccessfulInferenceKeyMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"label":"test","is_free_tier":false}}"""))
            val api = OpenRouterApi(OkHttpClient(), server.url("/api/v1").toString().trimEnd('/'))
            assertTrue(api.verifyKey("test-key").startsWith("Verified"))
            val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/api/v1/key", request.path)
        }
        assertThrows(java.io.IOException::class.java) { OpenRouterApi.validateMetadata(JSONObject("""{"data":{"label":"test","is_free_tier":false,"is_management_key":true}}""")) }
        Unit
    }
    @Test fun errorBodiesAreNotExposed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-data"))
            try { OpenRouterApi(OkHttpClient(),server.url("/").toString()).verifyKey("key"); fail("Should reject") }
            catch (e: OpenRouterApi.HttpError) { assertFalse(e.message!!.contains("sensitive")) }
        }
    }
    @Test fun coroutineCancellationCancelsHttpRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient(); val api = OpenRouterApi(client, server.url("/").toString())
            val job = launch(Dispatchers.IO) { api.verifyKey("key") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            job.cancelAndJoin(); assertTrue(job.isCancelled)
        }
    }
    @Test fun wavHeaderMatchesCheapWhisperAudioContract() {
        val header = java.nio.ByteBuffer.wrap(AudioFiles.wavHeader(32000)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, header.getInt(24)); assertEquals(32000, header.getInt(28)); assertEquals(32000, header.getInt(40))
    }
}
