package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.isAuthenticDecisionModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DecisionsApiTest {
    private lateinit var api: DecisionsAPI
    private lateinit var provider: OpenAIProvider
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val client = OkHttpClient()
        val keyRoulette = KeyRoulette.default()
        api = DecisionsAPI(client, keyRoulette)
        provider = OpenAIProvider(client, context = null)
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

    @Test
    fun `beam model is treated as chat and not decision model`() {
        val beamModel = Model(
            modelId = "reflection/beam-free",
            displayName = "reflection/beam-free",
            type = ModelType.CHAT,
        )
        val providerSetting = ProviderSetting.OpenAI(
            apiKey = "sk-test",
            baseUrl = "https://api.kilo.ai/api/gateway",
        )
        val params = TextGenerationParams(model = beamModel)
        assertFalse(provider.isDecisionRequest(params, providerSetting))
    }

    @Test
    fun `authentic decision families are strictly jev and laya`() {
        assertTrue(isAuthenticDecisionModel("free/jev-1.13"))
        assertTrue(isAuthenticDecisionModel("convaiinnovations/laya-free"))
        assertFalse(isAuthenticDecisionModel("reflection/beam-free"))
        assertFalse(isAuthenticDecisionModel("laguna-7b"))
        assertFalse(isAuthenticDecisionModel("gpt-4o"))
    }

    @Test
    fun `user settings override auto-detect for decision and chat models`() {
        val providerSetting = ProviderSetting.OpenAI(
            apiKey = "sk-test",
            baseUrl = "https://api.example.com/v1",
        )

        // When user chooses CHAT for a decision model, user setting persists and overrides auto-detect
        val jevModelAsChat = Model(
            modelId = "free/jev-1.13",
            displayName = "free/jev-1.13",
            type = ModelType.CHAT,
        )
        assertFalse(provider.isDecisionRequest(TextGenerationParams(model = jevModelAsChat), providerSetting))

        val layaModelAsChat = Model(
            modelId = "convaiinnovations/laya-free",
            displayName = "convaiinnovations/laya-free",
            type = ModelType.CHAT,
        )
        assertFalse(provider.isDecisionRequest(TextGenerationParams(model = layaModelAsChat), providerSetting))

        // When user chooses DECISION for a chat model, user setting persists and overrides auto-detect
        val beamModelAsDecision = Model(
            modelId = "reflection/beam-free",
            displayName = "reflection/beam-free",
            type = ModelType.DECISION,
        )
        assertTrue(provider.isDecisionRequest(TextGenerationParams(model = beamModelAsDecision), providerSetting))

        // Explicit DECISION model
        val explicitDecision = Model(
            modelId = "custom-model",
            displayName = "Custom Model",
            type = ModelType.DECISION,
        )
        assertTrue(provider.isDecisionRequest(TextGenerationParams(model = explicitDecision), providerSetting))
    }
}
