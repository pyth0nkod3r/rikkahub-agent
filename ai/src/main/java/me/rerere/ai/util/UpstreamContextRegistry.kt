package me.rerere.ai.util

import me.rerere.ai.provider.Model
import okhttp3.Headers
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic Upstream Context Auto-Discovery registry.
 *
 * Records model context-window limits discovered at runtime from upstream HTTP response headers
 * (e.g. `x-context-length`, `x-model-context-length`, `openai-context-length`) or provider
 * context-overflow error payloads (e.g. `"longer than the model's context length (524288 tokens)"`).
 *
 * Uses a lock-free [ConcurrentHashMap] bounded to 512 entries so lookups are O(1) with zero
 * main-thread or GC pressure.
 */
object UpstreamContextRegistry {
    private const val MAX_ENTRIES = 512
    private const val MIN_VALID_CONTEXT_TOKENS = 1_024
    private const val MAX_VALID_CONTEXT_TOKENS = 10_000_000

    private val discoveredLimits = ConcurrentHashMap<String, Int>()

    private val contextHeaderNames = listOf(
        "x-context-length",
        "x-model-context-length",
        "openai-context-length",
        "x-openrouter-context-length",
    )

    private val errorContextPatterns = listOf(
        Regex("""context\s+length\s*\(\s*(\d{4,8})\s*tokens?\s*\)""", RegexOption.IGNORE_CASE),
        Regex("""maximum\s+context\s+length\s+is\s+(\d{4,8})\s*tokens?""", RegexOption.IGNORE_CASE),
        Regex("""max[_\s]context[_\s](?:length|window|tokens?)\s*[:=]\s*(\d{4,8})""", RegexOption.IGNORE_CASE),
        Regex("""context\s+window\s+of\s+(\d{4,8})\s*tokens?""", RegexOption.IGNORE_CASE),
        Regex("""model's\s+maximum\s+context\s+length\s+is\s+(\d{4,8})""", RegexOption.IGNORE_CASE),
    )

    fun record(modelId: String, contextLength: Int, providerId: String? = null) {
        if (modelId.isBlank()) return
        if (contextLength !in MIN_VALID_CONTEXT_TOKENS..MAX_VALID_CONTEXT_TOKENS) return
        if (discoveredLimits.size >= MAX_ENTRIES) {
            discoveredLimits.keys.firstOrNull()?.let(discoveredLimits::remove)
        }
        val normalizedModel = modelId.trim().lowercase()
        discoveredLimits[normalizedModel] = contextLength
        if (!providerId.isNullOrBlank()) {
            discoveredLimits["${providerId.trim().lowercase()}::$normalizedModel"] = contextLength
        }
    }

    fun recordFromHeaders(modelId: String, headers: Headers, providerId: String? = null) {
        if (modelId.isBlank()) return
        for (headerName in contextHeaderNames) {
            val rawValue = headers[headerName]?.trim() ?: continue
            val parsed = rawValue.toIntOrNull()
            if (parsed != null && parsed in MIN_VALID_CONTEXT_TOKENS..MAX_VALID_CONTEXT_TOKENS) {
                record(modelId = modelId, contextLength = parsed, providerId = providerId)
                return
            }
        }
    }

    fun recordFromError(modelId: String, errorText: String?, providerId: String? = null): Int? {
        if (modelId.isBlank() || errorText.isNullOrBlank()) return null
        for (pattern in errorContextPatterns) {
            val match = pattern.find(errorText) ?: continue
            val limit = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            if (limit in MIN_VALID_CONTEXT_TOKENS..MAX_VALID_CONTEXT_TOKENS) {
                record(modelId = modelId, contextLength = limit, providerId = providerId)
                return limit
            }
        }
        return null
    }

    fun getDiscoveredContextLength(modelId: String, providerId: String? = null): Int? {
        if (modelId.isBlank()) return null
        val normalizedModel = modelId.trim().lowercase()
        if (!providerId.isNullOrBlank()) {
            discoveredLimits["${providerId.trim().lowercase()}::$normalizedModel"]?.let { return it }
        }
        return discoveredLimits[normalizedModel]
    }

    /**
     * Returns the effective context length for [model]:
     * - If an upstream limit was discovered at runtime, uses the smaller (safer) ceiling when the
     *   configured metadata overestimated the true upstream limit, or fills in missing metadata.
     */
    fun resolveContextLength(model: Model, providerId: String? = null): Int? {
        val discovered = getDiscoveredContextLength(model.modelId, providerId)
        val configured = model.contextLength?.takeIf { it > 0 }
        return when {
            discovered != null && configured != null -> minOf(discovered, configured)
            discovered != null -> discovered
            else -> configured
        }
    }

    internal fun clearForTesting() {
        discoveredLimits.clear()
    }
}
