package com.example.smartnotetaker

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var audioFile: File? = null

    /** Live peak amplitude of the most recent buffer, normalized 0f..1f (for mic-level UI). */
    @Volatile
    var amplitude: Float = 0f
        private set

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun start() {
        audioFile = File(context.cacheDir, "note_audio.wav")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true

        Thread {
            writeAudioDataToFile()
        }.start()
    }

    fun stop(): File? {
        isRecording = false
        amplitude = 0f
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        updateWavHeader(audioFile!!)
        return audioFile
    }

    private fun writeAudioDataToFile() {
        val data = ByteArray(bufferSize)
        val os = FileOutputStream(audioFile)
        
        // Write dummy header
        val header = ByteArray(44)
        os.write(header, 0, 44)

        while (isRecording) {
            val read = audioRecord?.read(data, 0, bufferSize) ?: 0
            if (read > 0) {
                os.write(data, 0, read)
                // Track peak amplitude over this buffer (16-bit little-endian samples).
                var peak = 0
                var i = 0
                while (i + 1 < read) {
                    val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)
                    val abs = if (sample < 0) -sample else sample
                    if (abs > peak) peak = abs
                    i += 2
                }
                amplitude = (peak / 32768f).coerceIn(0f, 1f)
            }
        }
        amplitude = 0f
        os.close()
    }

    private fun updateWavHeader(file: File) {
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * 1 * 16 / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = 1.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (1 * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        val randomAccessFile = RandomAccessFile(file, "rw")
        randomAccessFile.seek(0)
        randomAccessFile.write(header)
        randomAccessFile.close()
    }

    suspend fun decodeWavToFloatArray(file: File): FloatArray = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        // Skip 44 bytes header
        val pcmBytes = bytes.sliceArray(44 until bytes.size)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val floatArray = FloatArray(shortBuffer.capacity())
        for (i in 0 until shortBuffer.capacity()) {
            floatArray[i] = shortBuffer.get(i).toFloat() / 32768.0f // normalize to -1.0 .. 1.0
        }
        floatArray
    }
}
