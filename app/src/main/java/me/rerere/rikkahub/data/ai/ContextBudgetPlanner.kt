package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

data class ContextBudgetPlan(
    val estimatedInputTokens: Int,
    val triggerTokens: Int,
    val shouldCompact: Boolean,
)

/**
 * Conservative provider-independent context estimator.
 *
 * The most recent real usage is preferred because it includes provider-specific system and
 * tool-schema overhead. Messages added after that response are estimated locally. Providers
 * that do not report usage fall back to estimating the complete message list.
 */
object ContextBudgetPlanner {
    private const val CHARS_PER_TOKEN = 3
    private const val MESSAGE_OVERHEAD_TOKENS = 8
    private const val MEDIA_PART_TOKENS = 1_024
    private const val TOOL_SCHEMA_BASE_OVERHEAD_TOKENS = 24

    fun plan(
        messages: List<UIMessage>,
        contextLength: Int,
        thresholdPercent: Int,
        thresholdTokensK: Int? = null,
        reservedTokens: Int = 0,
    ): ContextBudgetPlan {
        require(contextLength > 0) { "contextLength must be positive" }
        val normalizedThreshold = thresholdPercent.coerceIn(5, 95)
        val safeInputBudget = (contextLength.toLong() - reservedTokens.coerceAtLeast(0).toLong())
            .coerceAtLeast(1L)
        val configuredTriggerTokens = thresholdTokensK?.let { tokensK ->
            (tokensK.toLong().coerceAtLeast(1L) * 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } ?: (contextLength.toLong() * normalizedThreshold / 100L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val triggerTokens = minOf(configuredTriggerTokens.toLong(), safeInputBudget)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val estimatedInputTokens = estimateInputTokens(messages)
        return ContextBudgetPlan(
            estimatedInputTokens = estimatedInputTokens,
            triggerTokens = triggerTokens,
            shouldCompact = estimatedInputTokens >= triggerTokens,
        )
    }

    fun estimateInputTokens(messages: List<UIMessage>): Int {
        val usageIndex = messages.indexOfLast { message ->
            val usage = message.usage
            usage != null && (usage.totalTokens > 0 || usage.promptTokens + usage.completionTokens > 0)
        }
        val estimate = if (usageIndex >= 0) {
            val usage = messages[usageIndex].usage!!
            val reportedTotal = usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)
            // Tool outputs are attached to the assistant message only after the provider has
            // reported usage for its tool-call response. They therefore are not represented in
            // that usage figure, even though the next model request includes them.
            reportedTotal.toLong() +
                estimatePostUsageToolOutputTokens(messages[usageIndex]) +
                messages.drop(usageIndex + 1).sumOf(::estimateMessageTokens)
        } else {
            messages.sumOf(::estimateMessageTokens)
        }
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Estimates the context represented by the supplied messages without using provider usage.
     * This is required after compaction because reported usage belongs to the pre-compaction
     * request and cannot describe the synthetic summary that replaced it.
     */
    fun estimateContextTokens(messages: List<UIMessage>): Int = messages
        .sumOf(::estimateMessageTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    /**
     * Estimates the token overhead of active tool definitions (names, descriptions, and schemas).
     */
    fun estimateToolSchemasTokens(tools: List<Tool>): Int {
        if (tools.isEmpty()) return 0
        val total = tools.sumOf { tool ->
            TOOL_SCHEMA_BASE_OVERHEAD_TOKENS +
                estimateTextTokens(tool.name) +
                estimateTextTokens(tool.description)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Pre-flight token estimator that accounts for message history, system prompt, and active tool
     * schema overhead before dispatching an HTTP request.
     */
    fun estimatePreFlightInputTokens(
        messages: List<UIMessage>,
        systemPrompt: String = "",
        tools: List<Tool> = emptyList(),
        isCompactedView: Boolean = false,
    ): Int {
        val systemOverhead = if (systemPrompt.isNotBlank()) {
            MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(systemPrompt)
        } else 0L
        val toolSchemaOverhead = estimateToolSchemasTokens(tools).toLong()
        val heuristicTotal = (estimateContextTokens(messages).toLong() + systemOverhead + toolSchemaOverhead)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (isCompactedView) {
            return heuristicTotal
        }
        return maxOf(estimateInputTokens(messages), heuristicTotal)
    }

    fun estimateMessageTokens(message: UIMessage): Long {
        val contentTokens = message.parts.sumOf(::estimatePartTokens)
        return MESSAGE_OVERHEAD_TOKENS + contentTokens
    }

    /**
     * Estimates only the execution results that were appended after provider usage was emitted.
     * Tool-call names and arguments already belong to the assistant completion represented by the
     * reported usage, so counting them here would double-count that response.
     */
    @Suppress("DEPRECATION")
    private fun estimatePostUsageToolOutputTokens(message: UIMessage): Long = message.parts.sumOf { part ->
        when (part) {
            is UIMessagePart.Tool -> part.output.sumOf(::estimatePartTokens)
            is UIMessagePart.ToolResult -> estimateTextTokens(part.content.toString())
            else -> 0L
        }
    }

    /** Selects a recent tail whose estimated size fits [targetTokens]. */
    fun chooseTailStartIndex(
        messages: List<UIMessage>,
        targetTokens: Int,
        minimumRecentMessages: Int = 4,
    ): Int {
        if (messages.size <= 1) return 0

        val minimumStart = (messages.size - minimumRecentMessages.coerceAtLeast(1))
            .coerceAtLeast(0)
        var start = messages.lastIndex
        var used = 0L
        while (start >= 0) {
            val next = estimateMessageTokens(messages[start])
            if (start < minimumStart && used + next > targetTokens) break
            used += next
            start--
        }
        return (start + 1).coerceIn(1, messages.lastIndex)
    }

    @Suppress("DEPRECATION")
    private fun estimatePartTokens(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
        is UIMessagePart.Tool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input) + part.output.sumOf(::estimatePartTokens)
        is UIMessagePart.ServerTool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input.toString()) + estimateTextTokens(part.output.toString())
        is UIMessagePart.ToolCall -> estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)
        is UIMessagePart.ToolResult -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.arguments.toString()) + estimateTextTokens(part.content.toString())
        is UIMessagePart.Document -> MEDIA_PART_TOKENS + estimateTextTokens(part.fileName)
        is UIMessagePart.Image,
        is UIMessagePart.Video,
        is UIMessagePart.Audio,
        UIMessagePart.Search,
            -> MEDIA_PART_TOKENS.toLong()
    }

    private fun estimateTextTokens(text: String): Long {
        var asciiChars = 0L
        var nonAsciiChars = 0L
        text.forEach { char ->
            if (char.code <= 0x7F) asciiChars++ else nonAsciiChars++
        }
        return nonAsciiChars + (asciiChars + CHARS_PER_TOKEN - 1L) / CHARS_PER_TOKEN
    }
}
