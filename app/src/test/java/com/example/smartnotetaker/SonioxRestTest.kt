package com.example.smartnotetaker

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SonioxRestTest {
    private val server = MockWebServer()
    private lateinit var wav: File

    @Before fun setUp() {
        server.start()
        wav = File.createTempFile("rec", ".wav").apply { writeBytes(ByteArray(44) + pcm(1600)) }
    }

    @After fun tearDown() {
        server.shutdown()
        wav.delete()
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private fun transcribe(language: String = "auto") = runBlocking {
        SonioxRest.transcribe(testClient(), "sx-key", wav, language, server.url("/").toString(), pollIntervalMs = 10)
    }

    private fun take(): RecordedRequest = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) ?: error("no request")

    @Test
    fun `upload, create, poll, fetch transcript, then clean up`() {
        server.enqueue(json(201, """{"id":"f1","filename":"rec.wav","size":3244}"""))
        server.enqueue(json(201, """{"id":"t1","status":"queued"}"""))
        server.enqueue(json(200, """{"id":"t1","status":"processing"}"""))
        server.enqueue(json(200, """{"id":"t1","status":"completed","audio_duration_ms":16079}"""))
        server.enqueue(json(200, """{"id":"t1","text":" Hello world ","tokens":[]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        val (text, seconds) = transcribe("es")
        assertEquals("Hello world", text)
        assertEquals(16.079, seconds!!, 1e-9)

        val upload = take()
        assertEquals("POST", upload.method)
        assertEquals("/v1/files", upload.path)
        assertEquals("Bearer sx-key", upload.getHeader("Authorization"))
        assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val multipart = upload.body.readUtf8()
        assertTrue(multipart.contains("name=\"file\"; filename=\"${wav.name}\""))
        assertTrue(multipart.contains("Content-Type: audio/wav"))

        val create = take()
        assertEquals("POST", create.method)
        assertEquals("/v1/transcriptions", create.path)
        assertEquals("Bearer sx-key", create.getHeader("Authorization"))
        val body = JSONObject(create.body.readUtf8())
        assertEquals("stt-async-v5", body.getString("model"))
        assertEquals("f1", body.getString("file_id"))
        assertEquals("es", body.getJSONArray("language_hints").getString(0))

        assertEquals("GET /v1/transcriptions/t1", take().let { "${it.method} ${it.path}" })
        assertEquals("GET /v1/transcriptions/t1", take().let { "${it.method} ${it.path}" })
        assertEquals("GET /v1/transcriptions/t1/transcript", take().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /v1/transcriptions/t1", take().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /v1/files/f1", take().let { "${it.method} ${it.path}" })
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `auto language sends no hints and missing duration yields null seconds`() {
        server.enqueue(json(201, """{"id":"f1"}"""))
        server.enqueue(json(201, """{"id":"t1","status":"queued"}"""))
        server.enqueue(json(200, """{"id":"t1","status":"completed"}"""))
        server.enqueue(json(200, """{"id":"t1","text":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        val (text, seconds) = transcribe("auto")
        assertEquals("ok", text)
        assertEquals(null, seconds)
        take()
        assertFalse(JSONObject(take().body.readUtf8()).has("language_hints"))
    }

    @Test
    fun `HTTP error on upload throws IOException with the status code and stops there`() {
        server.enqueue(json(401, """{"status_code":401,"error_type":"unauthenticated","message":"Incorrect API key"}"""))
        try {
            transcribe()
            fail("should throw")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("401"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `failed job throws with the provider message and still cleans up`() {
        server.enqueue(json(201, """{"id":"f1"}"""))
        server.enqueue(json(201, """{"id":"t1","status":"queued"}"""))
        server.enqueue(json(200, """{"id":"t1","status":"error","error_type":"invalid_audio","error_message":"Could not decode audio"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        try {
            transcribe()
            fail("should throw")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("Could not decode audio"))
        }
        repeat(3) { take() }
        assertEquals("DELETE /v1/transcriptions/t1", take().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /v1/files/f1", take().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `HTTP error while polling throws with the status code`() {
        server.enqueue(json(201, """{"id":"f1"}"""))
        server.enqueue(json(201, """{"id":"t1","status":"queued"}"""))
        server.enqueue(json(500, """{"message":"boom"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        try {
            transcribe()
            fail("should throw")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("500"))
        }
    }
}
