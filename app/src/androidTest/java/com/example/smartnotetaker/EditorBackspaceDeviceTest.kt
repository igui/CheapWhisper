package com.example.smartnotetaker

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorBackspaceDeviceTest {
    @Test fun removesExternalSelectionAndComposingTextInNativeEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            editor.inputType = InputType.TYPE_CLASS_TEXT
            editor.setText("Text from another keyboard")
            editor.setSelection(editor.text.length)
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            assertTrue(EditorBackspace.delete(connection))
            assertEquals("Text from another keyboar", editor.text.toString())
            editor.setSelection(0, 5)
            assertTrue(EditorBackspace.delete(connection))
            assertEquals("from another keyboar", editor.text.toString())
            editor.setText("hello 😀")
            editor.setSelection(editor.text.length)
            connection.setComposingRegion(0,editor.text.length)
            assertTrue(EditorBackspace.delete(connection))
            assertEquals("hello ",editor.text.toString())
        }
    }
}
