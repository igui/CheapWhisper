package com.example.smartnotetaker.transcription

import org.junit.Assert.*
import org.junit.Test

class TranscriptSettingsTest {
    private val settings = TranscriptSettings("model/first", "First model", "auto")
    @Test fun matchingSettingsHideTheLabelEvenWhenAutoDetectionReportsALanguage() {
        assertNull(settings.labelIfDifferent("model/first", "auto", "en"))
    }
    @Test fun changingModelShowsOriginalModelAndOriginalLanguage() {
        assertEquals("Transcribed with First model · language: auto (English detected)", settings.labelIfDifferent("model/second", "auto", "english"))
    }
    @Test fun changingLanguageShowsTheTranscriptionSettings() {
        val spanish = TranscriptSettings("model/first", "First model", "es")
        assertEquals("Transcribed with First model · language: Spanish", spanish.labelIfDifferent("model/first", "it", "es-ES"))
    }
    @Test fun restoringBothSettingsHidesTheLabelAgain() {
        assertNotNull(settings.labelIfDifferent("model/second", "ca", null))
        assertNull(settings.labelIfDifferent("model/first", "auto", null))
    }
    @Test fun missingDetectionDoesNotInventALanguage() {
        assertEquals("Transcribed with First model · language: auto", settings.labelIfDifferent("model/second", "auto", "Not reported"))
    }
}
