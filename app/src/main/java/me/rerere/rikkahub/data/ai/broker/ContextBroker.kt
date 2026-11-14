package me.rerere.rikkahub.data.ai.broker

import android.util.Log

/**
 * Context Broker Pipeline.
 *
 * One centralized system deciding what a model is told about the world before it answers.
 * Implements:
 * 1. Modular, fault-isolated collectors for all prompt sources.
 * 2. Clear distinction between ORDER (rendering sequence) and PRIORITY (trimming survival).
 * 3. Strict token budgeting: Memories and Rules are pinned and always survive trimming.
 * 4. Fault isolation: Collector exceptions are recorded in [ContextBundle.errors] and never
 *    crash prompt assembly or prevent rules/memories from being injected.
 */
class ContextBroker(
    private val customCollectors: Map<ContextSource, ContextCollector> = emptyMap(),
) {
    companion object {
        private const val TAG = "ContextBroker"
        const val DEFAULT_MAX_TOKENS = 120_000
    }

    /**
     * Gathers all sources, executes collectors with fault isolation, applies token budget,
     * and returns the final [ContextBundle].
     */
    suspend fun assemble(request: ContextRequest): ContextBundle {
        val collectedItems = mutableListOf<ContextItem>()
        val errors = mutableMapOf<ContextSource, String>()

        for (source in ContextSource.ORDER) {
            try {
                val item = collectSource(source, request)
                if (item != null && item.text.isNotBlank()) {
                    collectedItems.add(item)
                }
            } catch (t: Throwable) {
                runCatching {
                    Log.w(TAG, "Collector for ${source.identifier} failed: ${t.message}", t)
                }
                errors[source] = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            }
        }

        // Apply budget trimming
        val maxTokens = if (request.maxPromptTokens > 0) request.maxPromptTokens else DEFAULT_MAX_TOKENS
        val totalRequested = collectedItems.sumOf { it.estimatedTokens }

        if (totalRequested <= maxTokens) {
            return ContextBundle(
                items = collectedItems,
                errors = errors,
                plan = BudgetPlan(
                    totalBudgetTokens = maxTokens,
                    requestedTokens = totalRequested,
                    usedTokens = totalRequested,
                    droppedSources = emptyList(),
                    wasOverBudget = false,
                )
            )
        }

        // Over budget: trim lowest priority non-pinned items first.
        val pinnedItems = collectedItems.filter { it.isPinned }
        val droppableItems = collectedItems.filterNot { it.isPinned }
            .sortedBy { it.priority } // Lowest priority first

        var currentUsed = collectedItems.sumOf { it.estimatedTokens }
        val dropped = mutableListOf<ContextSource>()
        val keptDroppable = droppableItems.toMutableList()

        for (item in droppableItems) {
            if (currentUsed <= maxTokens) break
            keptDroppable.remove(item)
            currentUsed -= item.estimatedTokens
            dropped.add(item.source)
        }

        val finalItems = (pinnedItems + keptDroppable).sortedBy { it.source.renderOrder }
        val finalUsed = finalItems.sumOf { it.estimatedTokens }

        return ContextBundle(
            items = finalItems,
            errors = errors,
            plan = BudgetPlan(
                totalBudgetTokens = maxTokens,
                requestedTokens = totalRequested,
                usedTokens = finalUsed,
                droppedSources = dropped,
                wasOverBudget = true,
            )
        )
    }

    private suspend fun collectSource(source: ContextSource, request: ContextRequest): ContextItem? {
        val custom = customCollectors[source]
        if (custom != null) {
            return custom.collect(request)
        }

        return when (source) {
            ContextSource.SYSTEM_INSTRUCTION -> {
                if (request.assistantPrompt.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.SYSTEM_INSTRUCTION,
                        text = request.assistantPrompt.trim(),
                        isPinned = true,
                        priority = ContextSource.SYSTEM_INSTRUCTION.defaultPriority,
                    )
                } else null
            }

            ContextSource.SUBAGENT_PROFILE -> {
                if (request.subAgentPrompt.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.SUBAGENT_PROFILE,
                        text = request.subAgentPrompt.trim(),
                        isPinned = false,
                        priority = ContextSource.SUBAGENT_PROFILE.defaultPriority,
                    )
                } else null
            }

            ContextSource.TOOL_GUIDANCE -> {
                val toolText = buildString {
                    if (request.toolPrompts.isNotEmpty()) {
                        appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
                        request.toolPrompts.forEachIndexed { index, toolPrompt ->
                            if (index > 0) appendLine()
                            append(toolPrompt.trim())
                        }
                    }
                }.trim()

                if (toolText.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.TOOL_GUIDANCE,
                        text = toolText,
                        isPinned = false,
                        priority = ContextSource.TOOL_GUIDANCE.defaultPriority,
                    )
                } else null
            }

            ContextSource.PROJECT_DOC -> {
                if (request.projectDoc.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.PROJECT_DOC,
                        text = request.projectDoc.trim(),
                        isPinned = false,
                        priority = ContextSource.PROJECT_DOC.defaultPriority,
                    )
                } else null
            }

            ContextSource.SKILLS -> {
                if (request.skillsPrompt.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.SKILLS,
                        text = request.skillsPrompt.trim(),
                        isPinned = false,
                        priority = ContextSource.SKILLS.defaultPriority,
                    )
                } else null
            }

            ContextSource.WORKSPACE_STATE -> {
                if (request.workspaceStatePrompt.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.WORKSPACE_STATE,
                        text = request.workspaceStatePrompt.trim(),
                        isPinned = false,
                        priority = ContextSource.WORKSPACE_STATE.defaultPriority,
                    )
                } else null
            }

            ContextSource.RECENT_CHATS -> {
                if (request.recentChatsPrompt.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.RECENT_CHATS,
                        text = request.recentChatsPrompt.trim(),
                        isPinned = false,
                        priority = ContextSource.RECENT_CHATS.defaultPriority,
                    )
                } else null
            }

            ContextSource.MEMORY -> {
                if (request.memoryPrompt.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.MEMORY,
                        text = request.memoryPrompt.trim(),
                        isPinned = true,
                        priority = ContextSource.MEMORY.defaultPriority,
                    )
                } else null
            }

            ContextSource.RULES -> {
                val fullRules = buildString {
                    if (request.rulesPrompt.isNotBlank()) {
                        append(request.rulesPrompt.trim())
                    }
                    if (!request.systemAddendum.isNullOrBlank()) {
                        if (isNotEmpty()) appendLine("\n")
                        append(request.systemAddendum.trim())
                    }
                }.trim()

                if (fullRules.isNotBlank()) {
                    ContextItem(
                        source = ContextSource.RULES,
                        text = fullRules,
                        isPinned = true,
                        priority = ContextSource.RULES.defaultPriority,
                    )
                } else null
            }
        }
    }
}
