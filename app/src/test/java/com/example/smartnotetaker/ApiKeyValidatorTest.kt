package com.example.smartnotetaker

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApiKeyValidatorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        ApiKeyValidator.baseUrlOverride = server.url("/").toString()
    }

    @Test fun `OpenRouter key uses its metadata endpoint and rejects rate limited checks`() {
        server.enqueue(MockResponse().setBody("""{"data":{"label":"test","is_free_tier":false}}"""))
        assertEquals(ApiKeyValidator.Outcome.Valid, validate(PROVIDER_OPENROUTER))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/key", request.path)
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(validate(PROVIDER_OPENROUTER) is ApiKeyValidator.Outcome.Invalid)
    }
    @Test fun `OpenRouter error responses do not expose provider payloads`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"sk-test-key"}}"""))
        val outcome = validate(PROVIDER_OPENROUTER)
        assertTrue(outcome is ApiKeyValidator.Outcome.Invalid)
        assertFalse(outcome.toString().contains("sk-test-key"))
    }

    @After fun tearDown() {
        ApiKeyValidator.baseUrlOverride = null
        server.shutdown()
    }

    private fun validate(provider: String, key: String = "sk-test-key") =
        runBlocking { ApiKeyValidator.validate(provider, key) }

    @Test
    fun `200 is Valid and sends the provider's auth header to the expected path`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"list","data":[]}"""))
        assertEquals(ApiKeyValidator.Outcome.Valid, validate(PROVIDER_OPENAI))
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer sk-test-key", req.getHeader("Authorization"))
    }

    @Test
    fun `LLM check retrieves the cleanup model by id`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"$OPENAI_LLM_MODEL","object":"model"}"""))
        assertEquals(ApiKeyValidator.Outcome.Valid, validate(PROVIDER_LLM))
        assertEquals("/v1/models/$OPENAI_LLM_MODEL", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `each provider uses its own auth header style`() {
        val cases = listOf(
            PROVIDER_DEEPGRAM to Triple("/v1/auth/token", "Authorization", "Token sk-test-key"),
            PROVIDER_GROQ to Triple("/openai/v1/models", "Authorization", "Bearer sk-test-key"),
            PROVIDER_ELEVENLABS to Triple("/v1/user", "xi-api-key", "sk-test-key"),
            PROVIDER_ASSEMBLYAI to Triple("/v2/transcript?limit=1", "Authorization", "sk-test-key"),
        )
        for ((provider, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            assertEquals(provider, ApiKeyValidator.Outcome.Valid, validate(provider))
            val req = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(provider, expected.first, req.path)
            assertEquals(provider, expected.third, req.getHeader(expected.second))
        }
    }

    @Test
    fun `401 with JSON error is Invalid containing the status and message, never the key`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Incorrect API key provided: sk-abc","type":"invalid_request_error"}}""")
        )
        val outcome = validate(PROVIDER_OPENAI, "sk-test-key")
        assertTrue(outcome.toString(), outcome is ApiKeyValidator.Outcome.Invalid)
        val reason = (outcome as ApiKeyValidator.Outcome.Invalid).reason
        assertTrue(reason, reason.contains("401"))
        assertTrue(reason, reason.contains("Incorrect API key provided"))
        assertFalse(reason, reason.contains("sk-test-key"))
    }

    @Test
    fun `401 error message is truncated to about 80 chars`() {
        val long = "x".repeat(300)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"$long"}}"""))
        val reason = (validate(PROVIDER_GROQ) as ApiKeyValidator.Outcome.Invalid).reason
        assertTrue(reason.length.toString(), reason.length < 100)
        assertTrue(reason, reason.startsWith("401"))
    }

    @Test
    fun `403 without a body is Invalid with the status`() {
        server.enqueue(MockResponse().setResponseCode(403))
        val reason = (validate(PROVIDER_ELEVENLABS) as ApiKeyValidator.Outcome.Invalid).reason
        assertTrue(reason, reason.startsWith("403"))
    }

    @Test
    fun `429 is Valid because the key was accepted`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit"}}"""))
        assertEquals(ApiKeyValidator.Outcome.Valid, validate(PROVIDER_ASSEMBLYAI))
    }

    @Test
    fun `other server errors are Invalid with HTTP code`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ApiKeyValidator.Outcome.Invalid("HTTP 503"), validate(PROVIDER_DEEPGRAM))
    }

    @Test
    fun `connection refused is Unreachable`() {
        val url = server.url("/").toString()
        server.shutdown()
        ApiKeyValidator.baseUrlOverride = url
        val outcome = validate(PROVIDER_OPENAI)
        assertTrue(outcome.toString(), outcome is ApiKeyValidator.Outcome.Unreachable)
        assertTrue((outcome as ApiKeyValidator.Outcome.Unreachable).reason.isNotBlank())
    }

    @Test
    fun `blank key is Invalid without any request`() {
        assertEquals(ApiKeyValidator.Outcome.Invalid("No key"), validate(PROVIDER_OPENAI, ""))
        assertEquals(ApiKeyValidator.Outcome.Invalid("No key"), validate(PROVIDER_LLM, "   "))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unknown provider is Invalid without any request`() {
        val outcome = validate(PROVIDER_LOCAL_TINY)
        assertTrue(outcome.toString(), outcome is ApiKeyValidator.Outcome.Invalid)
        assertEquals(0, server.requestCount)
    }
}
