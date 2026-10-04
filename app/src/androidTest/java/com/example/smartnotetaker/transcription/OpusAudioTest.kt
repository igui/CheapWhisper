package com.example.smartnotetaker.transcription

import android.media.MediaPlayer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OpusAudioTest {
    @Test fun oggOpusImportsDecodesAndPreparesForPlayback() = verify("voice-note.opus")
    @Test fun stereoWebmOpusImportsAsMonoWav() = verify("stereo.webm")
    private fun verify(name: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = File.createTempFile("opus-test-", ".media", context.cacheDir)
        var imported: ImportedAudio? = null
        try {
            instrumentation.context.assets.open("opus/$name").use { input -> original.outputStream().use { input.copyTo(it) } }
            assertTrue("Container bytes identify Opus despite opaque extension", AudioFiles.isOpus(original))
            imported = AudioFiles.import(context, Uri.fromFile(original))
            assertTrue(imported.file.name.endsWith(".wav"))
            assertTrue("Duration was ${imported.durationMs}", imported.durationMs in 1900..2100)
            val bytes = imported.file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, header.getShort(22).toInt()); assertEquals(16000, header.getInt(24))
            assertEquals(bytes.size - 44, header.getInt(40))
            assertTrue("PCM has non-silent samples", bytes.drop(44).any { it != 0.toByte() })
            val player = MediaPlayer()
            try {
                player.setDataSource(imported.file.absolutePath); player.prepare()
                assertTrue(player.duration in 1900..2100)
            } finally { player.release() }
            val leftovers = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("opus-decoded-") }
            assertTrue("Intermediate PCM should be deleted", leftovers.isEmpty())
        } finally { imported?.file?.delete(); original.delete() }
    }
}
