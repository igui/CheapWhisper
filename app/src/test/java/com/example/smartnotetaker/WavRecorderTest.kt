package com.example.smartnotetaker

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WavRecorderTest {
    private val recorder = WavRecorder(ApplicationProvider.getApplicationContext())

    @Test
    fun `header describes 16 kHz mono 16-bit PCM of the given length`() {
        val h = recorder.buildWavHeader(1000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("fmt ", String(h, 12, 4))
        assertEquals("data", String(h, 36, 4))
        val le = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000 + 36, le.getInt(4))       // RIFF chunk size
        assertEquals(16, le.getInt(16))             // fmt chunk size
        assertEquals(1, le.getShort(20).toInt())    // PCM
        assertEquals(1, le.getShort(22).toInt())    // mono
        assertEquals(16000, le.getInt(24))          // sample rate
        assertEquals(32000, le.getInt(28))          // byte rate
        assertEquals(2, le.getShort(32).toInt())    // block align
        assertEquals(16, le.getShort(34).toInt())   // bits per sample
        assertEquals(1000, le.getInt(40))           // data size
    }

    @Test
    fun `decodeWavToFloatArray normalises samples to -1 to 1`() = runBlocking {
        val samples = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort(it) }
        val file = File.createTempFile("wav", ".wav")
        file.writeBytes(recorder.buildWavHeader(pcm.array().size.toLong()) + pcm.array())

        val floats = recorder.decodeWavToFloatArray(file)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767f / 32768f, -1f), floats, 1e-6f)
        file.delete()
        Unit
    }
}
