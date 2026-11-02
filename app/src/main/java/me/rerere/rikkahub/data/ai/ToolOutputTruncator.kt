package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Structured Tool Output Truncation (Head + Tail Retention) and Aging Tool Output Decay.
 *
 * 1. **Head + Tail Truncation**: When terminal (`bash`, `run_command`) or file-reading tools
 *    produce massive text, retains the first [DEFAULT_HEAD_LINES] lines and the last
 *    [DEFAULT_TAIL_LINES] lines with `[... N lines omitted ...]` in the middle, preserving
 *    command headers and bottom stacktraces while preventing context window spikes.
 * 2. **Aging Tool Output Decay**: Within multi-step tool turns, keeps the most recent
 *    [DEFAULT_KEEP_RECENT_TOOL_CALLS] tool outputs intact and folds older oversized tool outputs
 *    in the outgoing model request view into a compact head/tail digest. The persisted
 *    conversation retains the original output for user inspection.
 */
object ToolOutputTruncator {
    const val DEFAULT_HEAD_LINES = 100
    const val DEFAULT_TAIL_LINES = 150
    const val DEFAULT_MAX_CHARS = 32_000
    const val DEFAULT_KEEP_RECENT_TOOL_CALLS = 4
    const val DEFAULT_AGED_MAX_CHARS = 1_800
    private const val AGED_HEAD_LINES = 25
    private const val AGED_TAIL_LINES = 25

    fun truncateStructured(
        text: String,
        headLines: Int = DEFAULT_HEAD_LINES,
        tailLines: Int = DEFAULT_TAIL_LINES,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        if (text.isEmpty()) return text
        val maxTotalLines = (headLines + tailLines).coerceAtLeast(1)
        val lines = text.lines()

        if (lines.size > maxTotalLines) {
            val safeHead = headLines.coerceAtLeast(1)
            val safeTail = tailLines.coerceAtLeast(1)
            val omittedLines = lines.size - safeHead - safeTail
            val headText = lines.take(safeHead).joinToString("\n")
            val tailText = lines.takeLast(safeTail).joinToString("\n")
            val combined = buildString(headText.length + tailText.length + 80) {
                append(headText)
                append("\n\n[... ")
                append(omittedLines)
                append(" lines omitted ...]\n\n")
                append(tailText)
            }
            return if (combined.length > maxChars) {
                truncateByChars(combined, maxChars)
            } else {
                combined
            }
        }

        return if (text.length > maxChars) {
            truncateByChars(text, maxChars)
        } else {
            text
        }
    }

    private fun truncateByChars(text: String, maxChars: Int): String {
        val safeMax = maxChars.coerceAtLeast(256)
        if (text.length <= safeMax) return text
        val headChars = safeMax * 2 / 5
        val tailChars = safeMax - headChars
        val omittedChars = text.length - headChars - tailChars
        return buildString(safeMax + 64) {
            append(text, 0, headChars)
            append("\n\n[... ")
            append(omittedChars)
            append(" chars omitted ...]\n\n")
            append(text, text.length - tailChars, text.length)
        }
    }

    fun truncateToolOutputParts(
        parts: List<UIMessagePart>,
        headLines: Int = DEFAULT_HEAD_LINES,
        tailLines: Int = DEFAULT_TAIL_LINES,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<UIMessagePart> = parts.map { part ->
        when (part) {
            is UIMessagePart.Text -> {
                val truncated = truncateStructured(part.text, headLines, tailLines, maxChars)
                if (truncated === part.text || truncated == part.text) part else part.copy(text = truncated)
            }
            else -> part
        }
    }

    /**
     * Returns a request-only view of [messages] where tool outputs older than the most recent
     * [keepRecentToolCalls] completed tool calls are folded into a compact head/tail preview
     * whenever they exceed [maxAgedChars].
     */
    fun decayAgedToolOutputs(
        messages: List<UIMessage>,
        keepRecentToolCalls: Int = DEFAULT_KEEP_RECENT_TOOL_CALLS,
        maxAgedChars: Int = DEFAULT_AGED_MAX_CHARS,
    ): List<UIMessage> {
        if (messages.isEmpty()) return messages
        val safeKeep = keepRecentToolCalls.coerceAtLeast(0)

        var totalCompletedTools = 0
        messages.forEach { msg ->
            msg.parts.forEach { part ->
                if (part is UIMessagePart.Tool &&
                    !ContextCompactionPresentation.isDisplayTool(part) &&
                    part.output.isNotEmpty()
                ) {
                    totalCompletedTools++
                }
            }
        }
        val decayCount = totalCompletedTools - safeKeep
        if (decayCount <= 0) return messages

        var seenCompletedTools = 0
        var anyModified = false
        val transformed = messages.map { msg ->
            var msgModified = false
            val newParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool &&
                    !ContextCompactionPresentation.isDisplayTool(part) &&
                    part.output.isNotEmpty()
                ) {
                    seenCompletedTools++
                    if (seenCompletedTools <= decayCount) {
                        val decayedOutput = part.output.map { outPart ->
                            if (outPart is UIMessagePart.Text && outPart.text.length > maxAgedChars) {
                                msgModified = true
                                anyModified = true
                                outPart.copy(
                                    text = truncateStructured(
                                        text = outPart.text,
                                        headLines = AGED_HEAD_LINES,
                                        tailLines = AGED_TAIL_LINES,
                                        maxChars = maxAgedChars,
                                    )
                                )
                            } else {
                                outPart
                            }
                        }
                        if (msgModified) part.copy(output = decayedOutput) else part
                    } else {
                        part
                    }
                } else {
                    part
                }
            }
            if (msgModified) msg.copy(parts = newParts) else msg
        }
        return if (anyModified) transformed else messages
    }
}
