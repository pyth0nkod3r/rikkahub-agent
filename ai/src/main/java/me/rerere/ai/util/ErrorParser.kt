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

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // Check for OpenRouter / API gateway metadata containing raw error details
            val errorObj = this["error"] as? JsonObject ?: this
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
                return HttpException("Provider returned error$prov: $detail")
            }

            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                val fieldVal = this[foundField]!!
                if (fieldVal is JsonPrimitive) {
                    val msg = fieldVal.jsonPrimitive.content
                    if (msg == "Provider returned error" && (rawDetail != null || providerName != null)) {
                        val detail = rawDetail ?: "unknown upstream error"
                        val prov = if (providerName != null) " ($providerName)" else ""
                        return HttpException("Provider returned error$prov: $detail")
                    }
                    HttpException(msg)
                } else {
                    fieldVal.parseErrorDetail()
                }
            } else {
                // 如果没有找到任何错误字段，检查是否是误传入的非错误对象 (例如决策结果对象)
                if (this["object"]?.jsonPrimitive?.contentOrNull == "decision" || this["answers"] != null) {
                    val modelName = this["model"]?.jsonPrimitive?.contentOrNull ?: "decision model"
                    HttpException("Endpoint returned decision result instead of streaming events for model '$modelName'")
                } else {
                    // 如果没有找到任何错误字段，序列化整个对象
                    HttpException(Json.encodeToString(JsonElement.serializer(), this))
                }
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容 (covers JsonNull too — it's a JsonPrimitive subclass)
            HttpException(this.jsonPrimitive.content)
        }
    }
}
