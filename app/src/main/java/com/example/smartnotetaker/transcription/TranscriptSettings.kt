package com.example.smartnotetaker.transcription

import com.example.smartnotetaker.TRANSCRIBE_LANGUAGES

data class TranscriptSettings(val modelId: String, val modelName: String, val language: String) {
    fun labelIfDifferent(selectedModelId: String?, selectedLanguage: String, detectedLanguage: String?): String? {
        if (modelId == selectedModelId && language == selectedLanguage) return null
        val requested = languageName(language)
        val detected = detectedLanguage?.takeIf { it.isNotBlank() && it != "Not reported" && it != "auto" }?.let(::languageName)
        val description = if (detected != null && !detected.equals(requested, ignoreCase = true)) "$requested ($detected detected)" else requested
        return "Transcribed with $modelName · language: $description"
    }
    private fun languageName(value: String): String {
        if (value == "auto") return "auto"
        return TRANSCRIBE_LANGUAGES.firstOrNull { it.second.equals(value.substringBefore('-'), true) || it.first.equals(value, true) }?.first ?: value
    }
}
