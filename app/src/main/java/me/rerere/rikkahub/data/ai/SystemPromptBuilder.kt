package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.broker.ContextBroker
import me.rerere.rikkahub.data.ai.broker.ContextRequest

/**
 * Single place for assembling the system prompt that is sent to every provider.
 * Backed by the modular, fault-isolated [ContextBroker] pipeline.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (memory, recent chats, per-call
 * addendum) that change between turns. This lets prompt caching work: auto-caching
 * providers (OpenAI/DeepSeek/Grok/Gemini) reuse the stable byte-prefix, and OpenRouter's
 * explicit cache_control breakpoint is placed at the stable/volatile boundary.
 */
class SystemPromptBuilder(
    val contextBroker: ContextBroker = ContextBroker(),
) {
    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: memory + recent chats + per-call addendum.
     * Either may be blank.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        projectDoc: String = "",
        rulesPrompt: String = "",
        subAgentPrompt: String = "",
        skillsPrompt: String = "",
        workspaceStatePrompt: String = "",
        maxPromptTokens: Int = ContextBroker.DEFAULT_MAX_TOKENS,
    ): Pair<String, String> {
        val bundle = runBlocking {
            contextBroker.assemble(
                ContextRequest(
                    assistantPrompt = assistantPrompt,
                    memoryPrompt = memoryPrompt,
                    recentChatsPrompt = recentChatsPrompt,
                    toolPrompts = toolPrompts,
                    systemAddendum = systemAddendum,
                    projectDoc = projectDoc,
                    rulesPrompt = rulesPrompt,
                    subAgentPrompt = subAgentPrompt,
                    skillsPrompt = skillsPrompt,
                    workspaceStatePrompt = workspaceStatePrompt,
                    maxPromptTokens = maxPromptTokens,
                )
            )
        }
        return bundle.renderSections()
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not split into cache blocks. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        projectDoc: String = "",
        rulesPrompt: String = "",
        subAgentPrompt: String = "",
        skillsPrompt: String = "",
        workspaceStatePrompt: String = "",
        maxPromptTokens: Int = ContextBroker.DEFAULT_MAX_TOKENS,
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt = assistantPrompt,
            memoryPrompt = memoryPrompt,
            recentChatsPrompt = recentChatsPrompt,
            toolPrompts = toolPrompts,
            systemAddendum = systemAddendum,
            projectDoc = projectDoc,
            rulesPrompt = rulesPrompt,
            subAgentPrompt = subAgentPrompt,
            skillsPrompt = skillsPrompt,
            workspaceStatePrompt = workspaceStatePrompt,
            maxPromptTokens = maxPromptTokens,
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }
}
