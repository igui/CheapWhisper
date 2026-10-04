package com.example.smartnotetaker.transcription

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

data class TranscriptWordRange(val cueIndex: Int, val start: Int, val end: Int, val startMs: Long)
class ContinuousTranscript(transcript: AudioTranscript) {
    val text = transcript.text
    val words: List<TranscriptWordRange>
    init {
        var cursor = 0
        words = transcript.cues.mapIndexedNotNull { index, cue ->
            val word = cue.text.trim()
            var start = if (word.isEmpty()) -1 else text.indexOf(word, cursor, ignoreCase = true)
            while (start >= 0 && ((requiresBoundary(word.first()) && start > 0 && requiresBoundary(text[start - 1])) ||
                (requiresBoundary(word.last()) && start + word.length < text.length && requiresBoundary(text[start + word.length])))) {
                start = text.indexOf(word, start + 1, ignoreCase = true)
            }
            if (start < 0) null else {
                cursor = start + word.length
                TranscriptWordRange(index, start, cursor, cue.startMs)
            }
        }
    }
    private fun requiresBoundary(character: Char): Boolean = character.isDigit() || Character.UnicodeScript.of(character.code) in setOf(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK,
        Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW
    )
    fun highlighted(active: Int, background: Color, foreground: Color): AnnotatedString = buildAnnotatedString {
        append(text)
        words.forEach { word ->
            addStringAnnotation("seek", word.startMs.toString(), word.start, word.end)
            if (word.cueIndex == active) addStyle(SpanStyle(background = background, color = foreground), word.start, word.end)
        }
    }
}
