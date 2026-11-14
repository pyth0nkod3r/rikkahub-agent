package me.rerere.rikkahub.data.ai.broker

/**
 * Context source identifiers.
 * One declaration of every context source that can be gathered into the prompt.
 *
 * Distinguishes [renderOrder] (where a block prints in the assembled prompt)
 * from [defaultPriority] (which blocks survive token trimming when the context ceiling is tight).
 */
enum class ContextSource(
    val identifier: String,
    val defaultPriority: Int,
    val renderOrder: Int,
    val isPinned: Boolean = false,
) {
    // Pinned sources: highest priorities (90-100), never dropped during token budget trimming.
    // Standing rules render last as the final, authoritative instruction block.
    RULES("rules", defaultPriority = 100, renderOrder = 8, isPinned = true),
    MEMORY("memory", defaultPriority = 90, renderOrder = 6, isPinned = true),
    SYSTEM_INSTRUCTION("system_instruction", defaultPriority = 80, renderOrder = 0, isPinned = true),

    // Situational sources: prioritised and subject to token budgeting.
    SUBAGENT_PROFILE("subagent_profile", defaultPriority = 70, renderOrder = 1),
    TOOL_GUIDANCE("tool_guidance", defaultPriority = 60, renderOrder = 2),
    PROJECT_DOC("project_doc", defaultPriority = 50, renderOrder = 3),
    SKILLS("skills", defaultPriority = 40, renderOrder = 4),
    WORKSPACE_STATE("workspace_state", defaultPriority = 30, renderOrder = 5),
    RECENT_CHATS("recent_chats", defaultPriority = 20, renderOrder = 7);

    companion object {
        val ORDER: List<ContextSource> = entries.sortedBy { it.renderOrder }
        val PINNED: Set<ContextSource> = entries.filter { it.isPinned }.toSet()

        fun fromIdentifier(id: String): ContextSource? =
            entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
    }
}
