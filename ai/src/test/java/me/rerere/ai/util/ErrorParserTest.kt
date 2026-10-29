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
        val ex = element.parseErrorDetail()
        assertEquals("Provider returned error (DeepInfra): Service unavailable upstream", ex.message)
    }

    @Test
    fun `standard error returns direct message`() {
        val payload = """
            {
              "error": {
                "message": "Rate limit exceeded",
                "type": "requests"
              }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val ex = element.parseErrorDetail()
        assertEquals("Rate limit exceeded", ex.message)
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
        val ex = element.parseErrorDetail()
        assertTrue(ex.message!!.contains("decision result"))
        assertTrue(ex.message!!.contains("free/jev-1.13"))
    }
}
