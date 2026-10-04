package com.example.smartnotetaker

import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorBackspaceTest {
    private fun editor(text: String): Pair<BaseInputConnection, SpannableStringBuilder> {
        val editable = SpannableStringBuilder(text)
        Selection.setSelection(editable, editable.length)
        val connection = object : BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true) {
            override fun getEditable() = editable
        }
        return connection to editable
    }
    @Test fun deletesTextAlreadyInEditorWithoutDictationHistory() {
        val (connection, text) = editor("Typed by another keyboard")
        assertTrue(EditorBackspace.delete(connection)); assertEquals("Typed by another keyboar", text.toString())
    }
    @Test fun deletesSelectedTextInsteadOfCharactersAroundIt() {
        val (connection, text) = editor("one two three")
        Selection.setSelection(text, 4, 7)
        assertTrue(EditorBackspace.delete(connection)); assertEquals("one  three", text.toString())
    }
    @Test fun finishesAnotherKeyboardsCompositionBeforeDeleting() {
        val (connection, text) = editor("hello")
        connection.setComposingRegion(0, 5)
        assertTrue(EditorBackspace.delete(connection)); assertEquals("hell", text.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(text))
    }
    @Test fun emojiDeletionDoesNotLeaveHalfASurrogatePair() {
        val (connection, text) = editor("Hi 😀")
        assertTrue(EditorBackspace.delete(connection)); assertEquals("Hi ", text.toString())
    }
    @Test fun wordDeletionAndCursorStartAreHandled() {
        val (connection, text) = editor("one two  ")
        assertTrue(EditorBackspace.delete(connection, true)); assertEquals("one ", text.toString())
        Selection.setSelection(text,0); assertFalse(EditorBackspace.delete(connection)); assertEquals("one ",text.toString())
    }
    @Test fun fallsBackForEditorsWithoutCodePointDeletion() {
        val text = SpannableStringBuilder("external text"); Selection.setSelection(text,text.length)
        val connection = object : BaseInputConnection(View(ApplicationProvider.getApplicationContext()),true) {
            override fun getEditable() = text
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = false
        }
        assertTrue(EditorBackspace.delete(connection)); assertEquals("external tex",text.toString())
    }
}
