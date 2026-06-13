package com.example.smartnotetaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class LocalModelDownloader(private val context: Context) {
    suspend fun downloadModel(modelName: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        // Multilingual builds (no ".en" suffix) cover all ~99 Whisper languages.
        // Cache filename differs from the English-only ".en" builds, so no collision.
        val fileName = "ggml-${modelName}.bin"
        val modelFile = File(context.filesDir, fileName)
        
        if (modelFile.exists() && modelFile.length() > 0) {
            return@withContext modelFile
        }

        val urlStr = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${fileName}"
        downloadFile(urlStr, modelFile, onProgress)
    }

    suspend fun downloadLlmModel(modelChoice: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val isE2B = modelChoice.contains("E2B")
        val fileName = if (isE2B) "gemma-4-E2B-it.litertlm" else "gemma-4-E4B-it.litertlm"
        val repo = if (isE2B) "litert-community/gemma-4-E2B-it-litert-lm" else "litert-community/gemma-4-E4B-it-litert-lm"
        val modelFile = File(context.filesDir, fileName)
        
        if (modelFile.exists() && modelFile.length() > 500_000_000L) { // > 500MB approx check
            return@withContext modelFile
        }

        val urlStr = "https://huggingface.co/${repo}/resolve/main/${fileName}"
        downloadFile(urlStr, modelFile, onProgress)
    }

    private fun downloadFile(urlStr: String, destFile: File, onProgress: (Int) -> Unit): File? {
        try {
            var currentUrlStr = urlStr
            var redirectCount = 0
            var connection: HttpURLConnection? = null
            
            while (redirectCount < 5) {
                val url = URL(currentUrlStr)
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false // We handle it manually
                connection.connect()
                
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307) {
                    currentUrlStr = connection.getHeaderField("Location")
                    redirectCount++
                    continue
                }
                break
            }

            if (connection == null || connection.responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.e("SmartNoteTaker", "Download failed. HTTP code: \${connection?.responseCode}")
                return null
            }

            // Fallback for missing Content-Length header or very large files that overflow Int
            val fileLength = connection.contentLength.toLong()
            val fallbackLength = if (fileLength > 0) fileLength else {
                val clHeader = connection.getHeaderField("Content-Length")
                if (!clHeader.isNullOrEmpty()) {
                    clHeader.toLongOrNull() ?: 2588147712L // ~2.5GB fallback
                } else {
                    2588147712L
                }
            }

            val input = connection.inputStream
            val output = FileOutputStream(destFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            var lastProgress = -1

            while (input.read(data).also { count = it } != -1) {
                total += count
                val progress = ((total.toDouble() / fallbackLength.toDouble()) * 100).toInt()
                // Update UI on every single percent change, up to 100
                if (progress != lastProgress && progress <= 100) {
                    lastProgress = progress
                    onProgress(progress)
                }
                output.write(data, 0, count)
            }

            output.flush()
            output.close()
            input.close()
            
            return destFile
        } catch (e: Exception) {
            android.util.Log.e("SmartNoteTaker", "Download threw exception", e)
            if (destFile.exists()) destFile.delete()
            return null
        }
    }
}
