package me.rerere.rikkahub.data.ai.broker

/**
 * A measured, prioritised context block gathered by the Context Broker.
 *
 * @param source The source category.
 * @param text The rendered text content of the context item.
 * @param isPinned If true, this item can never be dropped during token budgeting (Rules & Memories).
 * @param priority Relative priority for budget trimming (higher survives longer).
 * @param estimatedTokens Estimated token count (roughly len / 4 characters for Latin/code).
 * @param metadata Contextual metadata (e.g. source paths, refresh timestamps, origin tags).
 */
data class ContextItem(
    val source: ContextSource,
    val text: String,
    val isPinned: Boolean = source.isPinned,
    val priority: Int = source.defaultPriority,
    val estimatedTokens: Int = estimateTokens(text),
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            return (text.length + 3) / 4
        }
    }
}
