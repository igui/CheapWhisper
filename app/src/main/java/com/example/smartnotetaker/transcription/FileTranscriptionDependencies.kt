package com.example.smartnotetaker.transcription

import android.content.Context
import android.net.Uri
import com.example.smartnotetaker.SecureStorage

open class FileTranscriptionDependencies(private val context: Context) {
    open suspend fun options(): Pair<String, String> = SecureStorage(context).let { it.getOpenRouterModel() to it.getTranscribeLanguage() }
    open suspend fun saveOptions(model: String, language: String) {
        SecureStorage(context).apply { saveOpenRouterModel(model); saveTranscribeLanguage(language) }
    }
    open suspend fun importAudio(uri: Uri): ImportedAudio = AudioFiles.import(context, uri)
    open suspend fun key(): String = SecureStorage(context).getOpenRouterApiKey()
    open suspend fun transcribe(audio: ImportedAudio, model: TranscriptionModel, language: String, key: String, status: (String) -> Unit): AudioTranscript =
        TranscriptionEngine(context).transcribe(audio.file, model, language, key, status)
}
