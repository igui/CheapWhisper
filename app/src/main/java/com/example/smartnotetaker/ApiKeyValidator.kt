package com.example.smartnotetaker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks an API key with the cheapest read-only request each provider offers (no audio,
 * no tokens billed). [provider] is a PROVIDER_* constant, or [PROVIDER_LLM] to check the
 * OpenAI key specifically for the cleanup model ([OPENAI_LLM_MODEL]).
 *
 * Endpoints (all GET, none billed):
 *  - OpenAI:     /v1/models                      (Bearer)
 *  - OpenAI LLM: /v1/models/{OPENAI_LLM_MODEL}   (Bearer; also proves model access)
 *  - Deepgram:   /v1/auth/token                  (Token)
 *  - Groq:       /openai/v1/models               (Bearer)
 *  - ElevenLabs: /v1/user                        (xi-api-key)
 *  - AssemblyAI: /v2/transcript?limit=1          (bare key in Authorization)
 *
 * Stateless apart from the shared client, so it is safe to call concurrently.
 * The key is never logged and never appears in an [Outcome] reason.
 */
object ApiKeyValidator {
    private const val TIMEOUT_SECONDS = 10L
    private const val MAX_REASON_CHARS = 80

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
        .build()

    /**
     * Test hook: when set, every provider's request goes to this origin (e.g. a MockWebServer
     * URL, with or without a trailing slash) instead of the real host. Production leaves it null.
     */
    @Volatile
    internal var baseUrlOverride: String? = null

    sealed class Outcome {
        object Valid : Outcome()
        data class Invalid(val reason: String) : Outcome()   // e.g. "401 Unauthorized"
        data class Unreachable(val reason: String) : Outcome() // network error: unknown validity
    }

    suspend fun validate(provider: String, key: String): Outcome {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return Outcome.Invalid("No key")
        if (provider == PROVIDER_OPENROUTER) {
            return try {
                com.example.smartnotetaker.transcription.OpenRouterApi(
                    client.newBuilder().followRedirects(false).followSslRedirects(false).build(),
                    url("https://openrouter.ai", "/api/v1")
                ).verifyKey(trimmed)
                Outcome.Valid
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: com.example.smartnotetaker.transcription.OpenRouterApi.HttpError) {
                if (e.status == 429 || e.status >= 500) Outcome.Unreachable("OpenRouter unavailable (HTTP ${e.status})")
                else Outcome.Invalid("HTTP ${e.status}: key could not be verified")
            }
            catch (_: IllegalArgumentException) { Outcome.Invalid("Invalid key format") }
            catch (_: Exception) { Outcome.Unreachable("Could not validate OpenRouter key") }
        }
        val request = try {
            buildRequest(provider, trimmed)
        } catch (e: IllegalArgumentException) {
            return Outcome.Invalid(e.message ?: "Unsupported provider")
        }
        return withContext(Dispatchers.IO) { execute(request) }
    }

    private fun buildRequest(provider: String, key: String): Request {
        val b = Request.Builder().get()
        return when (provider) {
            PROVIDER_OPENAI -> b.url(url("https://api.openai.com", "/v1/models"))
                .header("Authorization", "Bearer $key")
            PROVIDER_LLM -> b.url(url("https://api.openai.com", "/v1/models/$OPENAI_LLM_MODEL"))
                .header("Authorization", "Bearer $key")
            PROVIDER_DEEPGRAM -> b.url(url("https://api.deepgram.com", "/v1/auth/token"))
                .header("Authorization", "Token $key")
            PROVIDER_GROQ -> b.url(url("https://api.groq.com", "/openai/v1/models"))
                .header("Authorization", "Bearer $key")
            PROVIDER_ELEVENLABS -> b.url(url("https://api.elevenlabs.io", "/v1/user"))
                .header("xi-api-key", key)
            PROVIDER_ASSEMBLYAI -> b.url(url("https://api.assemblyai.com", "/v2/transcript?limit=1"))
                .header("Authorization", key)
            PROVIDER_SONIOX -> b.url(url("https://api.soniox.com", "/v1/models"))
                .header("Authorization", "Bearer $key")
            else -> throw IllegalArgumentException("Unsupported provider")
        }.build()
    }

    private fun url(realOrigin: String, path: String): String =
        (baseUrlOverride?.trimEnd('/') ?: realOrigin) + path

    private fun execute(request: Request): Outcome = try {
        client.newCall(request).execute().use { response ->
            val code = response.code
            when {
                response.isSuccessful -> Outcome.Valid
                // Rate-limited means the key was recognised; it just can't be exercised right now.
                code == 429 -> Outcome.Valid
                code == 401 || code == 403 -> {
                    val detail = errorMessage(response.body?.string())
                    val base = "$code ${response.message.ifBlank { if (code == 401) "Unauthorized" else "Forbidden" }}"
                    Outcome.Invalid(if (detail != null) "$base: $detail" else base)
                }
                else -> Outcome.Invalid("HTTP $code")
            }
        }
    } catch (e: IOException) {
        Outcome.Unreachable(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    /**
     * Pulls a human-readable message out of a provider error body. Handles the common shapes:
     * OpenAI/Groq `{"error":{"message":..}}`, Deepgram `{"err_msg":..}`, ElevenLabs
     * `{"detail":{"message":..}}` / `{"detail":".."}`, AssemblyAI `{"error":".."}`.
     */
    private fun errorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val text = try {
            val json = JSONObject(body)
            val error = json.opt("error")
            val detail = json.opt("detail")
            when {
                error is JSONObject -> error.optString("message").ifBlank { null }
                error is String && error.isNotBlank() -> error
                detail is JSONObject -> detail.optString("message").ifBlank { null }
                detail is String && detail.isNotBlank() -> detail
                else -> json.optString("err_msg").ifBlank { null }
                    ?: json.optString("message").ifBlank { null }
            }
        } catch (_: Exception) {
            null
        } ?: return null
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > MAX_REASON_CHARS) oneLine.take(MAX_REASON_CHARS - 1) + "…" else oneLine
    }
}
