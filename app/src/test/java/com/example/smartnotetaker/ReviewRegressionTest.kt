package com.example.smartnotetaker

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.smartnotetaker.transcription.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun model() = TranscriptionModel(JSONObject("""{"id":"test/model","name":"Test","publisher":"Test"}"""))
    @Test fun usageResetKeepsInFlightRequestsAndCompletionStillWorks() {
        val usage = TranscriptionUsage(context)
        val before = usage.begin(model(), 5.0)
        usage.complete(before, JSONObject("""{"seconds":5,"cost":0.1}"""), "before")
        val pending = usage.begin(model(), 3.0)
        usage.reset()
        assertEquals(BigDecimal.ZERO, usage.total())
        usage.complete(pending, JSONObject("""{"seconds":3,"cost":0.2}"""), "after")
        assertEquals(BigDecimal("0.2"), usage.total())
    }
    @Test fun resetCanClearHistoricalUnconfirmedRequests() {
        val usage = TranscriptionUsage(context); usage.reset()
        val interrupted = usage.begin(model(), 2.0)
        usage.release(interrupted)
        assertTrue(usage.summary().contains("unconfirmed"))
        usage.reset()
        assertFalse(usage.summary().contains("unconfirmed"))
    }
    @Test fun compactionPreservesExactTotalsAndPendingIds() {
        val usage = TranscriptionUsage(context); usage.reset()
        val pending = usage.begin(model(), 2.0)
        repeat(505) { val id = usage.begin(model(), 1.0); usage.complete(id, JSONObject("""{"seconds":1,"cost":0.000001}"""), "g$it") }
        assertEquals(0,BigDecimal("0.000505").compareTo(usage.total()))
        usage.complete(pending, JSONObject("""{"seconds":2,"cost":0.01}"""), "pending")
        assertEquals(0,BigDecimal("0.010505").compareTo(usage.total()))
        val file = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "openrouter-usage.json")
        assertTrue(org.json.JSONArray(file.readText()).length() < 500)
    }
    @Test fun retryAudioAndConfigurationSurviveServiceRestart() {
        val source = File.createTempFile("retry-source", ".wav", context.cacheDir).apply { writeBytes(ByteArray(32044) { 3 }) }
        try {
            val store = DictationRetryStore(context)
            val original = store.enqueue(source,"write","Draft","editor:1",PROVIDER_OPENROUTER,"es","model/saved","Local","prompt")
            store.save(original.copy(waitingForNetwork = true))
            val restored = DictationRetryStore(context).first()!!
            assertEquals(original.id,restored.id); assertTrue(restored.waitingForNetwork)
            assertEquals("model/saved",restored.openRouterModel); assertArrayEquals(source.readBytes(),restored.wav.readBytes())
            store.remove(restored); assertNull(store.first()); assertFalse(restored.wav.exists())
        } finally { source.delete() }
    }
    @Test fun anotherRecordingDoesNotDeleteAnEarlierRetryAndEditDestinationMustMatch() {
        val source = File.createTempFile("retry-many", ".wav", context.cacheDir).apply { writeBytes(ByteArray(32044)) }
        try {
            val store = DictationRetryStore(context)
            val first = store.enqueue(source,"modify","Original","editor:1","OpenAI","auto","model","OpenAI","prompt")
            val second = store.enqueue(source,"write","New","editor:2","OpenAI","auto","model","OpenAI","prompt")
            assertEquals(first.id,store.first()!!.id); assertTrue(first.wav.exists())
            assertEquals(second.id,store.forEditor("editor:2", "New")!!.id)
            assertEquals(first.id,store.forEditor("editor:1", "Original")!!.id)
            assertTrue(first.matches("editor:1","Original"))
            assertFalse(first.matches("editor:2","Original")); assertFalse(first.matches("editor:1","Edited"))
            store.remove(first); assertEquals(second.id,store.first()!!.id); store.remove(second)
        } finally { source.delete() }
    }
    @Test fun tinyTailsAreRedistributedWithoutDroppingSamplesOrExceedingMax() {
        val max = 60 * 32000
        for (tail in listOf(2, 1600, 31998)) {
            val total = max.toLong() + tail
            val first = audioChunkSize(total,max)
            val second = audioChunkSize(total-first,max)
            assertEquals(total,first.toLong()+second); assertTrue(first<=max); assertTrue(second>=32000)
        }
    }
    @Test fun refreshedModelRemainsAvailableToKeyboardAndAfterRestart() {
        val fresh = TranscriptionModel(JSONObject("""{"id":"new/model","name":"New model","publisher":"New","priceUnit":"unknown"}"""))
        TranscriptionCatalog.persist(context,listOf(fresh))
        assertEquals("new/model",TranscriptionCatalog.resolve(TranscriptionCatalog.available(context),"new/model").id)
    }
    @Test fun alignmentSupportsLanguagesWithoutWhitespace() {
        for (text in listOf("你好世界", "こんにちは世界", "ภาษาไทย")) {
            val first = text.take(2); val second = text.drop(2)
            val result = ContinuousTranscript(AudioTranscript(text,"auto",listOf(TimedCue(0,100,first),TimedCue(100,200,second))))
            assertEquals(2,result.words.size)
            assertEquals(listOf(0,2),result.words.map { it.start })
        }
    }
    @Test fun malformedRetryRowDoesNotBlockNewRecordings() {
        val store = DictationRetryStore(context)
        val directory = File(context.noBackupFilesDir,"pending-dictations")
        File(directory,"index.json").writeText("[{\"id\":\"broken\"}]")
        assertNull(store.first())
        val source = File.createTempFile("valid-retry", ".wav", context.cacheDir).apply { writeBytes(ByteArray(32044)) }
        try {
            val row = store.enqueue(source,"write","","editor","OpenAI","en","model","OpenAI","prompt")
            assertEquals(row.id,store.first()!!.id); store.remove(row)
        } finally { source.delete() }
    }

    @Test fun cueDoesNotMatchInsideAnUnrelatedLongerWord() {
        val transcript = ContinuousTranscript(AudioTranscript("there he goes", "en", listOf(TimedCue(0,100,"he"))))
        assertEquals(6,transcript.words.single().start)
        assertEquals("there he goes",transcript.text)
    }
}
