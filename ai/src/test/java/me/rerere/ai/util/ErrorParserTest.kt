package me.rerere.ai.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unpacks openrouter provider returned error with json raw metadata`() {
        val payload = """
            {
              "error": {
                "message": "Provider returned error",
                "code": 400,
                "metadata": {
                  "raw": "{\"error\":{\"message\":\"input: Invalid input: expected string, received array\"}}",
                  "provider_name": "Together"
                }
              }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val ex = element.parseErrorDetail()
        assertEquals("Provider returned error (Together): input: Invalid input: expected string, received array", ex.message)
        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `unpacks openrouter provider returned error with plain text raw metadata`() {
        val payload = """
            {
              "error": {
                "message": "Provider returned error",
                "metadata": {
                  "raw": "Service unavailable upstream",
                  "provider_name": "DeepInfra"
                }
              }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val ex = element.parseErrorDetail(statusCode = 503)
        assertEquals("Provider returned error (DeepInfra): Service unavailable upstream", ex.message)
        assertEquals(503, ex.statusCode)
    }

    @Test
    fun `standard error returns direct message and propagated status code`() {
        val payload = """
            {
              "error": {
                "message": "Rate limit exceeded",
                "type": "requests"
              }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val ex = element.parseErrorDetail(statusCode = 429)
        assertEquals("Rate limit exceeded", ex.message)
        assertEquals(429, ex.statusCode)
    }

    @Test
    fun `decision object returned unexpectedly is detected gracefully`() {
        val payload = """
            {
              "object": "decision",
              "model": "free/jev-1.13",
              "answers": {
                "is_safe": {"type": "noul", "noul": 0.99}
              }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val ex = element.parseErrorDetail(statusCode = 200)
        assertTrue(ex.message!!.contains("decision result"))
        assertTrue(ex.message!!.contains("free/jev-1.13"))
        assertEquals(200, ex.statusCode)
    }

    @Test
    fun `extracts status code from error code field when explicit status code is null`() {
        val payload = """
            {
              "error": {
                "message": "Invalid 'tools': array too long. Expected at most 128 items",
                "code": 400
              }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val ex = element.parseErrorDetail()
        assertEquals(400, ex.statusCode)
        assertTrue(ex.message!!.contains("array too long"))
    }
}
