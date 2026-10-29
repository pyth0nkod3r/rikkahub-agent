package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DecisionsApiTest {
    private lateinit var api: DecisionsAPI
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        api = DecisionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `formats apinex decision answers into clean markdown`() {
        val payload = """
            {
              "assessment": {"type": "noul", "noul": 0.68},
              "is_safe": {"type": "noul", "noul": 0.99},
              "category": {
                "type": "choice",
                "choice": "general",
                "probabilities": {"coding": 0, "general": 1, "analysis": 0},
                "confidence": 1.0
              },
              "complexity": {
                "type": "score",
                "score": 0,
                "legend": {"0": "trivial", "1": "easy", "2": "moderate", "3": "hard"},
                "confidence": 1.0
              }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val formatted = api.formatDecisionAnswers(element)

        assertTrue(formatted.contains("### Decision Output"))
        assertTrue(formatted.contains("Assessment") && formatted.contains("0.68 (68.0%)"))
        assertTrue(formatted.contains("Is Safe") && formatted.contains("0.99 (99.0%)"))
        assertTrue(formatted.contains("Category") && formatted.contains("`general`") && formatted.contains("Confidence: 100%"))
        assertTrue(formatted.contains("Complexity") && formatted.contains("`0` - trivial"))
    }

    @Test
    fun `formats array answers into clean markdown`() {
        val payload = """
            [
              {
                "name": "correctness_assessment",
                "type": "score",
                "score": 2,
                "confidence": 0.95
              }
            ]
        """.trimIndent()

        val element = json.parseToJsonElement(payload)
        val formatted = api.formatDecisionAnswers(element)

        assertTrue(formatted.contains("Correctness Assessment") && formatted.contains("`2`") && formatted.contains("Confidence: 95%"))
    }
}
