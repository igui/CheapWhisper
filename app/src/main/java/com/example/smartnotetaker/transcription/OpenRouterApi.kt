package com.example.smartnotetaker.transcription

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenRouterApi(private val client: OkHttpClient = defaultClient(), private val baseUrl: String = "https://openrouter.ai/api/v1") {
    data class Reply(val json: JSONObject, val generation: String?)
    class HttpError(val status: Int, val unsupportedTimestamps: Boolean = false) : IOException(when (status) {
        401, 403 -> "OpenRouter rejected the API key or permissions. Verify it in Settings."
        402 -> "OpenRouter credits or key spending limit exhausted."
        400, 415, 422 -> "The model rejected these options. Try another model or language."
        429 -> "OpenRouter rate limit reached. Retry later."
        408, 504, 524 -> "OpenRouter timed out. Try a shorter clip or another model."
        else -> "OpenRouter request failed (HTTP $status)."
    })
    fun uploadRequest(model: TranscriptionModel, language: String, key: String, audio: File): Request = request(model, language, key, audio, true)
    private fun request(model: TranscriptionModel, language: String, key: String, audio: File, timestamps: Boolean): Request {
        require(audio.length() <= 25_000_000) { "Audio chunk exceeds the upload limit." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", model.id)
            .addFormDataPart("response_format", if (timestamps) "verbose_json" else "json")
        if (language != "auto" && language.isNotBlank()) body.addFormDataPart("language", language)
        if (timestamps) body.addFormDataPart("timestamp_granularities[]", "word").addFormDataPart("timestamp_granularities[]", "segment")
        body.addFormDataPart("file", "audio.wav", audio.asRequestBody("audio/wav".toMediaType()))
        return Request.Builder().url("$baseUrl/audio/transcriptions").header("Authorization", "Bearer $key")
            .header("X-Title", "CheapWhisper").post(body.build()).build()
    }
    suspend fun transcribeChunk(model: TranscriptionModel, language: String, key: String, audio: File, seconds: Double, usage: TranscriptionUsage): Reply {
        suspend fun submit(timestamps: Boolean): Reply {
            val request = request(model, language, key, audio, timestamps)
            val id = usage.begin(model, seconds)
            try {
                val reply = try { execute(request) } catch (e: HttpError) { usage.reject(id); throw e }
                usage.complete(id, reply.json.optJSONObject("usage"), reply.generation)
                return reply
            } finally { usage.release(id) }
        }
        return try { submit(true) } catch (e: HttpError) {
            if (!e.unsupportedTimestamps) throw e
            submit(false)
        }
    }
    suspend fun execute(request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("OpenRouter connection failed. Check your connection and retry.")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) {
                            val errorBody = it.body
                            val message = if (it.code == 400 && errorBody != null && !errorBody.source().request(65537)) {
                                runCatching { JSONObject(errorBody.string()).optJSONObject("error")?.optString("message").orEmpty().lowercase() }.getOrDefault("")
                            } else ""
                            val timingRejected = ("timestamp" in message || "verbose_json" in message) &&
                                listOf("not support", "unsupported", "only support", "not available", "cannot return").any { reason -> reason in message }
                            throw HttpError(it.code, timingRejected)
                        }
                        val body = it.body ?: throw IOException("Empty OpenRouter response.")
                        if (body.source().request(32L * 1024 * 1024 + 1)) throw IOException("OpenRouter response is too large.")
                        val reply = Reply(JSONObject(body.string()), it.header("X-Generation-Id"))
                        if (continuation.isActive) continuation.resume(reply)
                    }
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    suspend fun verifyKey(key: String): String {
        require(key.isNotBlank()) { "Enter an OpenRouter key." }
        val json = execute(Request.Builder().url("$baseUrl/key").header("Authorization", "Bearer ${key.trim()}").build()).json
        return validateMetadata(json)
    }
    suspend fun catalog(): JSONObject = execute(Request.Builder().url("$baseUrl/models?output_modalities=transcription").build()).json
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(3, java.util.concurrent.TimeUnit.MINUTES).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        fun validateMetadata(json: JSONObject): String {
            val data = json.optJSONObject("data") ?: throw IOException("Invalid key validation response.")
            if (!data.has("label") || !data.has("is_free_tier")) throw IOException("Invalid key validation response.")
            if (data.optBoolean("is_management_key") || data.optBoolean("is_provisioning_key")) throw IOException("Use an inference key, not a management key.")
            val expires = data.optString("expires_at", "")
            if (expires.isNotBlank() && expires != "null" && Instant.parse(expires).isBefore(Instant.now())) throw IOException("This key has expired.")
            if (data.optJSONArray("allowed_data_regions")?.length() == 0) throw IOException("No inference regions allowed for this key.")
            return if (decimal(data.opt("limit_remaining"))?.signum() == 0) "Verified · spending limit exhausted" else "Verified with OpenRouter"
        }
    }
}
