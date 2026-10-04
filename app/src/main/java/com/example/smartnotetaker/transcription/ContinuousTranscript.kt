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
            val start = if (word.isEmpty()) -1 else text.indexOf(word, cursor, ignoreCase = true)
            if (start < 0) null else {
                cursor = start + word.length
                TranscriptWordRange(index, start, cursor, cue.startMs)
            }
        }
    }
    fun highlighted(active: Int, background: Color, foreground: Color): AnnotatedString = buildAnnotatedString {
        append(text)
        words.forEach { word ->
            addStringAnnotation("seek", word.startMs.toString(), word.start, word.end)
            if (word.cueIndex == active) addStyle(SpanStyle(background = background, color = foreground), word.start, word.end)
        }
    }
}
