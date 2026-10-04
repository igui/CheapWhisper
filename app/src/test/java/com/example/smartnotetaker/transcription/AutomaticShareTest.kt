package com.example.smartnotetaker.transcription

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticShareTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private class Fake(app: Application) : FileTranscriptionDependencies(app) {
        var selected = "google/gemini-3.5-transcribe" to "ca"
        var savedKey = "test-key"
        var optionsGate: CompletableDeferred<Unit>? = null
        var importGate: CompletableDeferred<Unit>? = null
        val calls = CopyOnWriteArrayList<Triple<String,String,String>>()
        private val directory = app.cacheDir
        override suspend fun options(): Pair<String,String> { optionsGate?.await(); return selected }
        override suspend fun saveOptions(model: String, language: String) { selected = model to language }
        override suspend fun importAudio(uri: Uri): ImportedAudio {
            importGate?.await()
            return ImportedAudio(File.createTempFile("share-test", ".wav", directory), uri.lastPathSegment ?: "audio", 2000)
        }
        override suspend fun key() = savedKey
        override suspend fun transcribe(audio: ImportedAudio, model: TranscriptionModel, language: String, key: String, status: (String) -> Unit): AudioTranscript {
            calls += Triple(audio.name, model.id, language)
            return AudioTranscript("Result",language, emptyList())
        }
    }
    private fun share(name: String) = Intent(Intent.ACTION_SEND).setType("audio/opus").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://voice.example/$name"))
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!predicate() && System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Timed out", predicate())
    }
    private fun drain() { repeat(20) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) } }
    private fun cleanup(vm: FileTranscriptionViewModel) { ViewModelStore().also { it.put("test",vm); it.clear() } }
    @Test fun coldShareWaitsForSettingsThenTranscribesOnceWithSavedModelAndLanguage() {
        val fake = Fake(app); val gate = CompletableDeferred<Unit>(); fake.optionsGate = gate
        val vm = FileTranscriptionViewModel(app,fake)
        try {
            vm.acceptShare(share("note.opus")); drain(); assertTrue(fake.calls.isEmpty())
            gate.complete(Unit); await { vm.ui.value.transcript != null }
            assertEquals(listOf(Triple("note.opus","google/gemini-3.5-transcribe","ca")),fake.calls.toList())
            vm.reloadSettings(); drain(); assertEquals(1,fake.calls.size)
        } finally { cleanup(vm) }
    }
    @Test fun manualImportStillWaitsForTranscribeButton() {
        val fake = Fake(app); val vm = FileTranscriptionViewModel(app,fake)
        try { vm.import(Uri.parse("content://voice.example/manual.opus")); await { vm.ui.value.audio != null && !vm.ui.value.busy }; assertTrue(fake.calls.isEmpty()) }
        finally { cleanup(vm) }
    }
    @Test fun missingKeyKeepsImportedAudioAndShowsSettingsHint() {
        val fake = Fake(app); fake.savedKey = ""; val vm = FileTranscriptionViewModel(app,fake)
        try {
            vm.acceptShare(share("note.opus")); await { vm.ui.value.audio != null && !vm.ui.value.busy }
            assertTrue(fake.calls.isEmpty()); assertTrue(vm.ui.value.status.contains("Settings"))
        } finally { cleanup(vm) }
    }
    @Test fun cancelDuringImportPreventsAutomaticUpload() {
        val fake = Fake(app); val gate = CompletableDeferred<Unit>(); fake.importGate = gate
        val vm = FileTranscriptionViewModel(app,fake)
        try {
            vm.acceptShare(share("cancel.opus")); drain(); vm.cancel(); gate.complete(Unit); drain()
            assertTrue(fake.calls.isEmpty()); assertFalse(vm.ui.value.busy)
        } finally { cleanup(vm) }
    }
    @Test fun replacementShareCannotUploadTheSupersededFile() {
        val fake = Fake(app); val gate = CompletableDeferred<Unit>(); fake.importGate = gate
        val vm = FileTranscriptionViewModel(app,fake)
        try {
            vm.acceptShare(share("first.opus")); drain(); vm.acceptShare(share("second.opus")); gate.complete(Unit)
            await { vm.ui.value.transcript != null }
            assertEquals(listOf("second.opus"),fake.calls.map { it.first })
        } finally { cleanup(vm) }
    }
    @Test fun warmShareUsesLatestSavedSelection() {
        val fake = Fake(app); val vm = FileTranscriptionViewModel(app,fake)
        try {
            await { vm.ui.value.ready }; fake.selected = "microsoft/mai-transcribe-2" to "es"
            vm.acceptShare(share("warm.opus")); await { vm.ui.value.transcript != null }
            assertEquals(Triple("warm.opus","microsoft/mai-transcribe-2","es"),fake.calls.single())
        } finally { cleanup(vm) }
    }
}
