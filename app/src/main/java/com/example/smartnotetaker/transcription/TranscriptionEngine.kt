package com.example.smartnotetaker.transcription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionEngine(private val context: Context, private val api: OpenRouterApi = OpenRouterApi()) {
    suspend fun transcribe(source: File, model: TranscriptionModel, language: String, key: String, onStatus: (String) -> Unit): AudioTranscript = withContext(Dispatchers.IO) {
        val pcm = File.createTempFile("decoded-", ".pcm", context.cacheDir)
        try {
            onStatus("Preparing audio…"); AudioFiles.decode(source, pcm)
            val usage = TranscriptionUsage(context); val result = TranscriptAccumulator()
            var consumed = 0L
            pcm.inputStream().buffered().use { input ->
                while (consumed < pcm.length()) {
                    currentCoroutineContext().ensureActive()
                    val size = minOf(model.maxChunkSeconds * 32000L, pcm.length() - consumed).toInt()
                    val chunk = File.createTempFile("upload-", ".wav", context.cacheDir)
                    try {
                        chunk.outputStream().buffered().use { output ->
                            output.write(AudioFiles.wavHeader(size)); val buffer = ByteArray(65536); var left = size
                            while (left > 0) {
                                currentCoroutineContext().ensureActive(); val read = input.read(buffer, 0, minOf(buffer.size, left))
                                check(read > 0) { "Unexpected end of decoded audio." }; output.write(buffer, 0, read); left -= read
                            }
                        }
                        onStatus("Transcribing audio… ${consumed * 100 / pcm.length()}%")
                        currentCoroutineContext().ensureActive()
                        val reply = api.transcribeChunk(model, language, key, chunk, size / 32000.0, usage)
                        val transcript = AudioTranscript.parse(reply.json)
                        result.append(transcript, consumed * 1000 / 32000)
                        consumed += size
                        onStatus("Transcribing audio… ${consumed * 100 / pcm.length()}%")
                    } finally { chunk.delete() }
                }
            }
            result.finish()
        } finally { pcm.delete() }
    }
}
