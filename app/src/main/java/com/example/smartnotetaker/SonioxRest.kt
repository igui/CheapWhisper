package com.example.smartnotetaker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val TAG = "SonioxRest"
private val JSON = "application/json".toMediaType()

/**
 * One-shot transcription of a recorded WAV through Soniox's async REST API
 * (https://soniox.com/docs/stt/api-reference). There is no single-call endpoint: the flow
 * is upload the file, create a transcription for it, poll until it completes, fetch the
 * transcript, then delete the transcription and the file so nothing lingers server-side.
 * Billed on audio duration, $0.10/h.
 */
object SonioxRest {
    const val BASE_URL = "https://api.soniox.com"
    const val MODEL = "stt-async-v5"

    /**
     * Returns (transcript, billed audio seconds or null). Throws [IOException] carrying the
     * HTTP status code on any failed request, or the provider's error message if the job
     * itself fails.
     */
    suspend fun transcribe(
        client: OkHttpClient,
        apiKey: String,
        audioFile: File,
        language: String,
        baseUrl: String = BASE_URL,
        pollIntervalMs: Long = 500,
        pollTimeoutMs: Long = 180_000,
    ): Pair<String, Double?> = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        fun req(path: String) = Request.Builder().url("$base$path").header("Authorization", "Bearer $apiKey")

        // 1. Upload the WAV.
        val upload = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val fileId = call(client, req("/v1/files").post(upload).build()).getString("id")
        var transcriptionId: String? = null
        try {
            // 2. Create the transcription job.
            val body = JSONObject().put("model", MODEL).put("file_id", fileId)
            if (language != "auto") body.put("language_hints", JSONArray().put(language))
            transcriptionId = call(client, req("/v1/transcriptions").post(body.toString().toRequestBody(JSON)).build())
                .getString("id")

            // 3. Poll until it completes.
            val deadline = System.currentTimeMillis() + pollTimeoutMs
            var status: JSONObject
            while (true) {
                status = call(client, req("/v1/transcriptions/$transcriptionId").get().build())
                when (status.optString("status")) {
                    "completed" -> break
                    "error" -> throw IOException("Soniox transcription failed: ${status.optString("error_message")}")
                }
                if (System.currentTimeMillis() > deadline) throw IOException("Soniox transcription timed out")
                delay(pollIntervalMs)
            }

            // 4. Fetch the transcript. audio_duration_ms is what Soniox bills on.
            val transcript = call(client, req("/v1/transcriptions/$transcriptionId/transcript").get().build())
                .getString("text").trim()
            val seconds = status.optDouble("audio_duration_ms").takeUnless { it.isNaN() }?.div(1000.0)
            transcript to seconds
        } finally {
            // 5. Best-effort cleanup; a failure here must not mask the result.
            transcriptionId?.let { tryDelete(client, req("/v1/transcriptions/$it").delete().build()) }
            tryDelete(client, req("/v1/files/$fileId").delete().build())
        }
    }

    private fun call(client: OkHttpClient, request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Soniox error: ${response.code} ${response.message}")
            JSONObject(response.body?.string() ?: throw IOException("Empty response"))
        }

    private fun tryDelete(client: OkHttpClient, request: Request) {
        try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) Log.w(TAG, "Cleanup ${request.url.encodedPath} returned ${r.code}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cleanup ${request.url.encodedPath} failed", e)
        }
    }
}
