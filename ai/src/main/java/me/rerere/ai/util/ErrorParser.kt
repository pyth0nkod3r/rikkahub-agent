package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String,
    val statusCode: Int? = null,
) : RuntimeException(message)

fun JsonElement.parseErrorDetail(statusCode: Int? = null): HttpException {
    return when (this) {
        is JsonObject -> {
            // Check for OpenRouter / API gateway metadata containing raw error details
            val errorObj = this["error"] as? JsonObject ?: this
            val resolvedStatusCode = statusCode
                ?: (errorObj["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: (errorObj["status"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: (this["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: (this["status"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

            val metadata = (errorObj["metadata"] ?: this["metadata"]) as? JsonObject
            val providerName = (metadata?.get("provider_name") as? JsonPrimitive)?.contentOrNull
            val raw = (metadata?.get("raw") as? JsonPrimitive)?.contentOrNull

            // If raw exists, see if it can be unpacked as JSON to get the real underlying error
            val rawDetail = if (!raw.isNullOrBlank()) {
                runCatching {
                    val parsed = Json.parseToJsonElement(raw)
                    if (parsed is JsonObject) {
                        (parsed["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: parsed["message"]?.jsonPrimitive?.contentOrNull
                            ?: parsed["detail"]?.jsonPrimitive?.contentOrNull
                            ?: raw
                    } else raw
                }.getOrDefault(raw)
            } else null

            // If message is "Provider returned error", enrich it with provider name and raw details
            val directMessage = (errorObj["message"] as? JsonPrimitive)?.contentOrNull
                ?: (this["message"] as? JsonPrimitive)?.contentOrNull
            if (directMessage == "Provider returned error" && (rawDetail != null || providerName != null)) {
                val detail = rawDetail ?: "unknown upstream error"
                val prov = if (providerName != null) " ($providerName)" else ""
                return HttpException("Provider returned error$prov: $detail", statusCode = resolvedStatusCode)
            }

            // 优先检查常见错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 找到第一个匹配的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                val fieldVal = this[foundField]!!
                if (fieldVal is JsonPrimitive) {
                    val msg = fieldVal.jsonPrimitive.content
                    if (msg == "Provider returned error" && (rawDetail != null || providerName != null)) {
                        val detail = rawDetail ?: "unknown upstream error"
                        val prov = if (providerName != null) " ($providerName)" else ""
                        return HttpException("Provider returned error$prov: $detail", statusCode = resolvedStatusCode)
                    }
                    HttpException(msg, statusCode = resolvedStatusCode)
                } else {
                    fieldVal.parseErrorDetail(statusCode = resolvedStatusCode)
                }
            } else {
                // 处理非预期返回决策结果（例如未按约定流式输出）
                if (this["object"]?.jsonPrimitive?.contentOrNull == "decision" || this["answers"] != null) {
                    val modelName = this["model"]?.jsonPrimitive?.contentOrNull ?: "decision model"
                    HttpException("Endpoint returned decision result instead of streaming events for model '$modelName'", statusCode = resolvedStatusCode)
                } else {
                    // 没有命中任何字段兜底输出完整 JSON
                    HttpException(Json.encodeToString(JsonElement.serializer(), this), statusCode = resolvedStatusCode)
                }
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array", statusCode = statusCode)
            } else {
                this.first().parseErrorDetail(statusCode = statusCode)
            }
        }

        is JsonPrimitive -> {
            HttpException(this.jsonPrimitive.content, statusCode = statusCode)
        }
    }
}
