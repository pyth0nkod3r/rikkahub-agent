package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Usage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.StreamChunk
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.await
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DecisionsAPI"

class DecisionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val endpoint = resolveEndpoint(providerSetting)
        val inputText = extractInputText(messages)
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBodyJson = buildJsonObject {
            put("model", params.model.modelId)
            put("input", inputText)
        }.mergeCustomBody(params.customBody)

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .headers(params.customHeaders.toHeaders())
            .addHeader("Content-Type", "application/json")

        if (key.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }

        requestBuilder.post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))

        val response = client.newCall(requestBuilder.build()).await()
        val bodyStr = response.body.string()

        if (!response.isSuccessful) {
            val bodyElement = runCatching { json.parseToJsonElement(bodyStr) }.getOrNull()
            if (bodyElement != null) {
                throw bodyElement.parseErrorDetail()
            } else {
                throw HttpException("HTTP ${response.code}: $bodyStr", statusCode = response.code)
            }
        }

        val bodyElement = json.parseToJsonElement(bodyStr)
        if (bodyElement !is JsonObject) {
            throw HttpException("Expected JSON object from decisions endpoint: $bodyStr")
        }

        // If the endpoint returned an error payload even with HTTP 200
        if (bodyElement["error"] != null) {
            throw bodyElement.parseErrorDetail()
        }

        val answersElement = bodyElement["answers"]
        val formattedMarkdown = if (answersElement != null) {
            formatDecisionAnswers(answersElement)
        } else {
            bodyStr
        }

        val usage = parseUsage(bodyElement["usage"])

        TextGenerationResult(
            text = formattedMarkdown,
            usage = usage,
        )
    }

    fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        // Decision models return complete structured decisions rather than SSE token streams.
        val result = generateText(providerSetting, messages, params)
        emit(StreamChunk.TextDelta(result.text))
        result.usage?.let { u ->
            emit(
                StreamChunk.Usage(
                    promptTokens = u.promptTokens,
                    completionTokens = u.completionTokens,
                    totalTokens = u.totalTokens,
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun resolveEndpoint(providerSetting: ProviderSetting.OpenAI): String {
        val base = providerSetting.baseUrl.trimEnd('/')
        // If chatCompletionsPath is already configured to point to decisions, honor it
        if (providerSetting.chatCompletionsPath.contains("decision", ignoreCase = true)) {
            val p = providerSetting.chatCompletionsPath.trimStart('/')
            return "$base/$p"
        }
        val path = providerSetting.decisionsPath.ifBlank { "/decisions" }.trimStart('/')
        return "$base/$path"
    }

    private fun extractInputText(messages: List<UIMessage>): String {
        val texts = messages.mapNotNull { msg ->
            val content = msg.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (content.isNotBlank()) {
                if (messages.size > 1) "${msg.role.name}: $content" else content
            } else null
        }
        return texts.joinToString("\n\n").ifBlank { "Evaluate input" }
    }

    private fun parseUsage(usageElement: JsonElement?): Usage? {
        val obj = usageElement as? JsonObject ?: return null
        val promptTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull
            ?: obj["prompt_tokens"]?.jsonPrimitive?.intOrNull
            ?: 0
        val completionTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull
            ?: obj["completion_tokens"]?.jsonPrimitive?.intOrNull
            ?: 0
        val totalTokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull
            ?: (promptTokens + completionTokens)
        return Usage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
        )
    }

    internal fun formatDecisionAnswers(answersElement: JsonElement): String {
        val sb = StringBuilder()
        sb.append("### Decision Output\n\n")
        when (answersElement) {
            is JsonObject -> {
                answersElement.forEach { (name, item) ->
                    formatAnswerItem(sb, name, item)
                }
            }
            is JsonArray -> {
                answersElement.forEach { item ->
                    val obj = item as? JsonObject
                    val name = obj?.get("name")?.jsonPrimitive?.contentOrNull ?: "Decision"
                    formatAnswerItem(sb, name, item)
                }
            }
            else -> sb.append(answersElement.toString()).append("\n")
        }
        return sb.toString().trim()
    }

    private fun formatAnswerItem(sb: StringBuilder, name: String, item: JsonElement) {
        val obj = item as? JsonObject
        if (obj == null) {
            sb.append("- **").append(name.replace('_', ' ').capitalizeWords()).append("**: ")
                .append(item.toString()).append("\n")
            return
        }

        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val readableName = name.replace('_', ' ').capitalizeWords()

        when (type) {
            "noul", "predicate" -> {
                val score = obj["noul"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["probability"]?.jsonPrimitive?.doubleOrNull
                if (score != null) {
                    val percent = (score * 100.0)
                    sb.append("- **").append(readableName).append("**: ")
                        .append(String.format(java.util.Locale.US, "%.2f (%.1f%%)", score, percent))
                        .append("\n")
                } else {
                    sb.append("- **").append(readableName).append("**: ").append(obj).append("\n")
                }
            }
            "choice" -> {
                val choice = obj["choice"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull
                sb.append("- **").append(readableName).append("**: `").append(choice).append("`")
                if (confidence != null) {
                    sb.append(" (Confidence: ").append((confidence * 100).toInt()).append("%)")
                }
                sb.append("\n")
                val probs = obj["probabilities"] as? JsonObject
                if (probs != null && probs.isNotEmpty()) {
                    val probStr = probs.entries.joinToString(", ") { (k, v) ->
                        val p = v.jsonPrimitive.doubleOrNull?.let { (it * 100).toInt() } ?: 0
                        "$k: $p%"
                    }
                    sb.append("  - Distribution: ").append(probStr).append("\n")
                }
            }
            "score" -> {
                val score = obj["score"]?.jsonPrimitive?.contentOrNull ?: "0"
                val legend = obj["legend"] as? JsonObject
                val legendLabel = legend?.get(score)?.jsonPrimitive?.contentOrNull
                val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull
                sb.append("- **").append(readableName).append("**: `").append(score).append("`")
                if (legendLabel != null) {
                    sb.append(" - ").append(legendLabel)
                }
                if (confidence != null) {
                    sb.append(" (Confidence: ").append((confidence * 100).toInt()).append("%)")
                }
                sb.append("\n")
            }
            else -> {
                val value = obj["value"]?.jsonPrimitive?.contentOrNull
                    ?: obj["result"]?.jsonPrimitive?.contentOrNull
                    ?: obj.toString()
                sb.append("- **").append(readableName).append("**: ").append(value).append("\n")
            }
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
        }
}
