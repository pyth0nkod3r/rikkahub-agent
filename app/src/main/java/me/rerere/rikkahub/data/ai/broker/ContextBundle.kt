package me.rerere.rikkahub.data.ai.broker

/**
 * Audit record of budget application when trimming context to fit token limit.
 */
data class BudgetPlan(
    val totalBudgetTokens: Int,
    val requestedTokens: Int,
    val usedTokens: Int,
    val droppedSources: List<ContextSource>,
    val wasOverBudget: Boolean,
)

/**
 * Result of Context Broker assembly.
 *
 * @param items The budgeted, measured items that survived trimming and will reach the model prompt.
 * @param errors Recorded collector failures (source -> error message).
 * @param plan The budget calculation and trimming record.
 */
data class ContextBundle(
    val items: List<ContextItem>,
    val errors: Map<ContextSource, String> = emptyMap(),
    val plan: BudgetPlan? = null,
) {
    /**
     * Splits into stable (cacheable prefix) and volatile sections to optimize KV prompt caching.
     *
     * Stable: Assistant persona, tools, project doc, subagent profile, skills.
     * Volatile: Memory, recent chats, workspace state, rules, dynamic addendum.
     */
    fun renderSections(): Pair<String, String> {
        val sorted = items.sortedBy { it.source.renderOrder }
        val stableSources = setOf(
            ContextSource.SYSTEM_INSTRUCTION,
            ContextSource.SUBAGENT_PROFILE,
            ContextSource.TOOL_GUIDANCE,
            ContextSource.PROJECT_DOC,
            ContextSource.SKILLS,
        )

        val stable = sorted
            .filter { it.source in stableSources && it.text.isNotBlank() }
            .joinToString("\n") { it.text.trim() }

        val volatile = sorted
            .filter { it.source !in stableSources && it.text.isNotBlank() }
            .joinToString("\n") { it.text.trim() }

        return stable to volatile
    }

    /**
     * Full combined prompt text ordered strictly by [ContextSource.renderOrder].
     */
    fun renderFullPrompt(): String {
        val (stable, volatile) = renderSections()
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * Checks if a particular source was successfully sent.
     */
    fun hasSource(source: ContextSource): Boolean =
        items.any { it.source == source && it.text.isNotBlank() }
}
