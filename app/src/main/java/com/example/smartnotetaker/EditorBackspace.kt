package com.example.smartnotetaker

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

object EditorBackspace {
    fun delete(connection: InputConnection, wholeWord: Boolean = false): Boolean {
        connection.beginBatchEdit()
        return try {
            connection.finishComposingText()
            val selected = connection.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                if (connection.commitText("", 1)) true else sendDelete(connection)
            } else {
                val before = connection.getTextBeforeCursor(if (wholeWord) 256 else 2, 0)?.toString()
                if (before != null && before.isEmpty()) false
                else {
                    val units = if (wholeWord && before != null) wordLength(before) else
                        if (before != null && before.length >= 2 && Character.isSurrogatePair(before[before.lastIndex - 1], before.last())) 2 else 1
                    val codePoints = if (before != null) before.codePointCount((before.length - units).coerceAtLeast(0), before.length) else 1
                    connection.deleteSurroundingTextInCodePoints(codePoints, 0) ||
                        connection.deleteSurroundingText(units, 0) || sendDelete(connection)
                }
            }
        } finally { connection.endBatchEdit() }
    }
    private fun wordLength(before: String): Int {
        var start = before.length
        while (start > 0 && before[start - 1].isWhitespace()) start--
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        if (start > 0 && Character.isLowSurrogate(before[start]) && Character.isHighSurrogate(before[start - 1])) start--
        return before.length - start
    }
    private fun sendDelete(connection: InputConnection): Boolean {
        val time = SystemClock.uptimeMillis()
        val down = connection.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
        val up = connection.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
        return down || up
    }
}
