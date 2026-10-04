package com.example.smartnotetaker.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

val APPLICATION_AUDIO_TYPES = listOf("application/ogg", "application/x-ogg", "application/opus", "application/x-opus", "application/octet-stream")
val AUDIO_PICKER_TYPES = (listOf("audio/*") + APPLICATION_AUDIO_TYPES).toTypedArray()

object SharedRecording {
    fun uri(intent: Intent): Uri {
        require(intent.action == Intent.ACTION_SEND) { "Share one audio attachment." }
        val mime = intent.type?.lowercase()?.substringBefore(';')
        require(mime == null || mime.startsWith("audio/") || mime in APPLICATION_AUDIO_TYPES) { "Share an audio file or voice note." }
        val stream = try { intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) } catch (_: RuntimeException) { null }
        val uri = if (stream != null) stream as? Uri else intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        require(uri != null && uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "No readable audio attachment. Share one file with read access." }
        return uri
    }
}

data class ImportedAudio(val file: File, val name: String, val durationMs: Long)
object AudioFiles {
    suspend fun import(context: Context, uri: Uri): ImportedAudio {
        var name = "Shared audio"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) require(it.getLong(sizeIndex) <= 100L * 1024 * 1024) { "Choose audio under 100 MiB." }
            }
        }
        val file = File.createTempFile("shared-audio-", ".media", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive(); val read = input.read(buffer); if (read < 0) break
                        total += read; require(total <= 100L * 1024 * 1024) { "Choose audio under 100 MiB." }; output.write(buffer, 0, read)
                    }
                    require(total > 0) { "The file is empty." }
                }
            } ?: throw IOException("The audio attachment cannot be read. Share it again.")
            if (isOpus(file)) {
                val normalized = normalizeOpus(context, file)
                file.delete()
                return ImportedAudio(normalized, name, (normalized.length() - 44) * 1000 / 32000)
            }
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: throw IOException("Android could not read this audio format.")
            } finally { retriever.release() }
            require(duration in 1..7200000) { "Choose audio under two hours." }
            currentCoroutineContext().ensureActive()
            return ImportedAudio(file, name, duration)
        } catch (e: Exception) { file.delete(); throw e }
    }

    fun isOpus(file: File): Boolean {
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(4096); val count = input.read(bytes)
            if (count > 0) bytes.copyOf(count) else byteArrayOf()
        }
        if (header.size >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "OggS" && String(header, Charsets.ISO_8859_1).contains("OpusHead")) return true
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/opus" }
        } catch (_: Exception) { false } finally { extractor.release() }
    }

    suspend fun normalizeOpus(context: Context, source: File): File {
        val pcm = File.createTempFile("opus-decoded-", ".pcm", context.cacheDir)
        var wav: File? = null
        try {
            decode(source, pcm)
            currentCoroutineContext().ensureActive()
            wav = File.createTempFile("opus-playback-", ".wav", context.cacheDir)
            wav.outputStream().buffered().use { output ->
                output.write(wavHeader(pcm.length().toInt()))
                pcm.inputStream().buffered().use { input ->
                    val bytes = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(bytes); if (count < 0) break
                        output.write(bytes, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return wav
        } catch (e: Exception) { wav?.delete(); throw e }
        finally { pcm.delete() }
    }

    suspend fun decode(source: File, output: File) {
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("No decodable audio track found.")
            val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT; var inputDone = false; var outputDone = false
            val info = MediaCodec.BufferInfo(); var frames = 0L; var nextOutput = 0.0; var previous = 0.0; var written = 0L
            var lastProgress = System.nanoTime()
            output.outputStream().buffered().use { sink ->
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() - lastProgress > 30_000_000_000) throw IOException("Audio decoding timed out.")
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val count = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                            if (count < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance() }
                            lastProgress = System.nanoTime()
                        }
                    }
                    when (val index = decoder.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val actual = decoder.outputFormat; val newRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            require(frames == 0L || newRate == rate) { "Changing sample rates are not supported." }
                            rate = newRate; channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoded PCM format." }
                        }
                        else -> if (index >= 0) {
                            val buffer = decoder.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                                val frameBytes = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                while (buffer.remaining() >= frameBytes) {
                                    var sample = 0.0
                                    repeat(channels) { sample += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float * 32767.0 else buffer.short.toDouble() }
                                    sample /= channels
                                    while (nextOutput <= frames) {
                                        val value = if (frames == 0L) sample else previous + (sample - previous) * (nextOutput - (frames - 1))
                                        val pcm = value.toInt().coerceIn(-32768, 32767); sink.write(pcm and 255); sink.write((pcm shr 8) and 255)
                                        written += 2; require(written <= 230_400_000) { "Decoded audio exceeds two hours." }; nextOutput += rate / 16000.0
                                    }
                                    previous = sample; frames++
                                }
                                lastProgress = System.nanoTime()
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; decoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            require(written > 0) { "No audio decoded." }
        } finally { codec?.release(); extractor.release() }
    }
    fun wavHeader(bytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1.toShort())
        .putShort(1.toShort()).putInt(16000).putInt(32000).putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(bytes).array()
}
