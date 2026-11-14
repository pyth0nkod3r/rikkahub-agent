package me.rerere.rikkahub.data.ai.broker

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant

/**
 * Request payload handed to the Context Broker to assemble prompt context.
 */
data class ContextRequest(
    val assistant: Assistant? = null,
    val model: Model? = null,
    val conversationId: String? = null,
    val assistantPrompt: String = "",
    val projectDoc: String = "",
    val memoryPrompt: String = "",
    val rulesPrompt: String = "",
    val subAgentPrompt: String = "",
    val skillsPrompt: String = "",
    val toolPrompts: List<String> = emptyList(),
    val recentChatsPrompt: String = "",
    val workspaceStatePrompt: String = "",
    val systemAddendum: String? = null,
    val maxPromptTokens: Int = 120_000,
    val userQuery: String? = null,
)
