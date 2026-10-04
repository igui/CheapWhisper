package com.example.smartnotetaker.transcription

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartnotetaker.SecureStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FileTranscriptionViewModel internal constructor(application: Application, private val dependencies: FileTranscriptionDependencies) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, FileTranscriptionDependencies(application))
    data class State(
        val models: List<TranscriptionModel> = emptyList(), val model: TranscriptionModel? = null,
        val language: String = "auto", val ready: Boolean = false,
        val audio: ImportedAudio? = null, val transcript: AudioTranscript? = null, val resultSettings: TranscriptSettings? = null,
        val busy: Boolean = false, val status: String = "Share a voice note or import audio.",
        val usage: String = "", val modelUsage: String = "", val catalog: String = "AA / pricing snapshot · 2026-10-03"
    )
    private val state = MutableStateFlow(State())
    val ui = state.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var optionsSave: Job? = null
    var playbackMs: Long = 0
    var exportDraft: String = ""
    init {
        val models = TranscriptionCatalog.distinct(TranscriptionCatalog.available(application))
        state.value = State(models = models, model = TranscriptionCatalog.resolve(models, TranscriptionCatalog.DEFAULT))
        reloadSettings()
    }
    fun reloadSettings() = viewModelScope.launch {
        try {
            val options = withContext(Dispatchers.IO) { dependencies.options() }
            if (!state.value.busy) state.update { it.copy(model = TranscriptionCatalog.resolve(it.models, options.first), language = options.second, ready = true) }
            refreshUsage()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (!state.value.busy) state.update { it.copy(status = "Unlock the phone to load encrypted settings.") } }
    }
    fun select(model: TranscriptionModel? = null, language: String? = null) {
        state.update { it.copy(model = model ?: it.model, language = language ?: it.language) }
        val snapshot = state.value
        val previousSave = optionsSave
        optionsSave = viewModelScope.launch(Dispatchers.IO) {
            previousSave?.join()
            snapshot.model?.let { dependencies.saveOptions(it.id, snapshot.language) }
        }
    }
    fun acceptShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        try { importAudio(SharedRecording.uri(intent), autoTranscribe = true) }
        catch (e: Exception) { state.update { it.copy(status = e.message ?: "Invalid audio attachment.") } }
    }
    fun import(uri: Uri) = importAudio(uri, autoTranscribe = false)
    private fun importAudio(uri: Uri, autoTranscribe: Boolean) {
        cancel(); val ticket = ++generation
        state.update { it.copy(busy = true, status = "Importing audio…") }
        job = viewModelScope.launch {
            var imported: ImportedAudio? = null
            var adopted = false
            try {
                optionsSave?.join()
                val options = withContext(Dispatchers.IO) { dependencies.options() }
                if (ticket != generation) return@launch
                state.update { it.copy(model = TranscriptionCatalog.resolve(it.models, options.first), language = options.second, ready = true) }
                val audio = withContext(Dispatchers.IO) { dependencies.importAudio(uri).also { imported = it } }
                if (ticket != generation) { withContext(Dispatchers.IO) { audio.file.delete() }; return@launch }
                val previous = state.value.audio; playbackMs = 0
                adopted = true
                state.update { it.copy(audio = audio, transcript = null, resultSettings = null, status = "Audio imported") }
                withContext(Dispatchers.IO) { previous?.file?.delete() }
                if (ticket != generation) return@launch
                state.update { it.copy(busy = false, status = "Ready to transcribe") }
                if (autoTranscribe) transcribe()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) state.update { it.copy(busy = false, status = if (e is SecurityException) "Audio access expired. Share it again." else e.message ?: "Could not import audio.") } }
            finally { if (!adopted) withContext(NonCancellable + Dispatchers.IO) { imported?.file?.delete() } }
        }
    }

    fun transcribe() {
        val snapshot = state.value; val audio = snapshot.audio ?: return; val model = snapshot.model ?: return
        if (snapshot.busy || !snapshot.ready) return
        val ticket = ++generation
        state.update { it.copy(busy = true, status = "Preparing transcription…") }
        job = viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { dependencies.key() }
                require(key.isNotBlank()) { "Open Settings and verify your OpenRouter API key." }
                val result = dependencies.transcribe(audio, model, snapshot.language, key) { message ->
                    viewModelScope.launch { if (ticket == generation) { state.update { it.copy(status = message) }; refreshUsage() } }
                }
                if (ticket == generation) state.update { it.copy(transcript = result, resultSettings = TranscriptSettings(model.id, model.name, snapshot.language), busy = false,
                    status = if (result.text.isBlank()) "No speech detected" else if (result.cues.isEmpty()) "Transcript ready" else "Transcript ready · tap a word to seek") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) state.update { it.copy(busy = false, status = e.message ?: "Transcription failed. Completed parts remain in usage totals.") } }
            finally { withContext(NonCancellable) { refreshUsage() } }
        }
    }
    fun cancel() {
        generation++; job?.cancel()
        state.update { it.copy(busy = false, status = if (it.busy) "Canceled · completed parts counted; interrupted requests may be unconfirmed." else it.status) }
    }
    fun refreshCatalog() = viewModelScope.launch {
        state.update { it.copy(catalog = "Refreshing catalog…") }
        try {
            val models = withContext(Dispatchers.IO) { TranscriptionCatalog.merge(OpenRouterApi().catalog().getJSONArray("data"), TranscriptionCatalog.all(getApplication())) }
            require(models.isNotEmpty())
            withContext(Dispatchers.IO) { TranscriptionCatalog.persist(getApplication(), models) }
            state.update { it.copy(models = models, model = TranscriptionCatalog.resolve(models, it.model?.id ?: TranscriptionCatalog.DEFAULT), catalog = "Live catalog · ${models.size} model families; AA snapshot 2026-10-03") }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.update { it.copy(catalog = "Refresh unavailable · keeping current catalog") } }
    }
    suspend fun refreshUsage() {
        try {
            val result = withContext(Dispatchers.IO) { TranscriptionUsage(getApplication()).let { it.summary() to it.summary(true) } }
            state.update { it.copy(usage = result.first, modelUsage = result.second) }
        } catch (_: Exception) { state.update { it.copy(usage = "Usage history unavailable") } }
    }
    fun export(uri: Uri, text: String) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Destination unavailable") }
            state.update { it.copy(status = "Transcript exported") }
        } catch (_: Exception) { state.update { it.copy(status = "Export failed. Try another destination.") } }
    }
    override fun onCleared() {
        val current = job; current?.cancel(); val file = state.value.audio?.file
        CoroutineScope(Dispatchers.IO).launch { current?.join(); file?.delete() }
        super.onCleared()
    }
}
