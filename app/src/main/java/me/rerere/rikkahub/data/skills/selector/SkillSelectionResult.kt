package me.rerere.rikkahub.data.skills.selector

import me.rerere.rikkahub.data.files.SkillMetadata

data class SelectedSkill(
    val skill: SkillMetadata,
    val tier: SkillTier,
    val matchedReason: String,
    val priority: Int = 50,
)

data class OmittedSkill(
    val skill: SkillMetadata,
    val reason: String, // "disabled", "omitted_ceiling", "omitted_budget", "not_relevant"
)

data class SkillSelectionResult(
    val selected: List<SelectedSkill>,
    val omitted: List<OmittedSkill>,
) {
    /**
     * Renders concise Markdown suitable for prompt injection via ContextBroker (ContextSource.SKILLS).
     */
    fun renderMarkdown(): String {
        if (selected.isEmpty()) return ""

        return buildString {
            appendLine("## Active Skills")
            appendLine("The following specialized skills have been selected for this turn based on your request and context:")
            appendLine()
            selected.forEach { item ->
                appendLine("### Skill: `${item.skill.name}` (${item.tier.identifier}: ${item.matchedReason})")
                if (item.skill.description.isNotBlank()) {
                    appendLine(item.skill.description.trim())
                }
                appendLine()
            }
        }.trimEnd()
    }
}
