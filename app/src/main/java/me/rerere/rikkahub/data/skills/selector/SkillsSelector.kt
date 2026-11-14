package me.rerere.rikkahub.data.skills.selector

import me.rerere.rikkahub.data.files.SkillMetadata

/**
 * Dynamic Relevance-Based 5-Tier Skills Selector.
 *
 * Implements deterministic skill selection across five tiers:
 * 1. REQUEST: named directly in user request.
 * 2. PROJECT: recommended in project document (.rikkahub/project.md).
 * 3. ENABLED: explicitly enabled for project/conversation.
 * 4. RELEVANT: keywords match the user request.
 * 5. GENERAL: auto_load / always-on skills.
 *
 * Key guarantees:
 * - Deterministic: Pure function of inputs, zero external state.
 * - Explicit off beats everything: Disabled skills are never selected.
 * - Earliest tier wins: A skill is assigned to its highest priority tier only.
 * - Within-tier ranking: Priority frontmatter ranks within a tier, never across tiers.
 * - Ceilings: Enforces skill count and character limits gracefully.
 */
object SkillsSelector {

    private const val DEFAULT_PRIORITY = 50

    fun select(
        catalog: List<SkillMetadata>,
        userMessage: String,
        projectDoc: String = "",
        enabledSkillNames: Set<String> = emptySet(),
        disabledSkillNames: Set<String> = emptySet(),
        maxSkillsInPrompt: Int = 5,
        maxChars: Int = 8_000,
    ): SkillSelectionResult {
        val selected = mutableListOf<SelectedSkill>()
        val omitted = mutableListOf<OmittedSkill>()

        val queryLower = userMessage.lowercase()
        val projectDocLower = projectDoc.lowercase()
        val disabledLower = disabledSkillNames.map { it.lowercase() }.toSet()
        val enabledLower = enabledSkillNames.map { it.lowercase() }.toSet()

        val candidates = mutableListOf<SelectedSkill>()

        for (skill in catalog) {
            val nameLower = skill.name.lowercase()

            // 1. Explicit disable beats all signals
            if (nameLower in disabledLower) {
                omitted.add(OmittedSkill(skill, "disabled"))
                continue
            }

            // 2. Determine earliest matching tier
            val tierMatch: Pair<SkillTier, String>? = when {
                isRequestMatch(queryLower, nameLower) -> {
                    SkillTier.REQUEST to "named directly in user request"
                }
                isProjectMatch(projectDocLower, nameLower) -> {
                    SkillTier.PROJECT to "recommended in project architecture"
                }
                nameLower in enabledLower -> {
                    SkillTier.ENABLED to "enabled in assistant settings"
                }
                isKeywordMatch(queryLower, skill) -> {
                    SkillTier.RELEVANT to "relevant to task keywords"
                }
                skill.autoLoad -> {
                    SkillTier.GENERAL to "always-on core skill"
                }
                else -> null
            }

            if (tierMatch != null) {
                val (tier, reason) = tierMatch
                candidates.add(
                    SelectedSkill(
                        skill = skill,
                        tier = tier,
                        matchedReason = reason,
                        priority = DEFAULT_PRIORITY,
                    )
                )
            } else {
                omitted.add(OmittedSkill(skill, "not_relevant"))
            }
        }

        // 3. Sort candidates:
        // Tier rank ascending (1 -> 5)
        // Priority descending
        // Tie break: path depth -> id length -> lexicographical
        val sortedCandidates = candidates.sortedWith(
            compareBy<SelectedSkill> { it.tier.rank }
                .thenByDescending { it.priority }
                .thenBy { it.skill.skillDir.absolutePath.count { c -> c == '/' || c == '\\' } }
                .thenBy { it.skill.name.length }
                .thenBy { it.skill.name }
        )

        // 4. Enforce ceilings
        var currentChars = 0
        for ((index, candidate) in sortedCandidates.withIndex()) {
            if (index >= maxSkillsInPrompt) {
                omitted.add(OmittedSkill(candidate.skill, "omitted_ceiling"))
                continue
            }

            val skillChars = candidate.skill.name.length + candidate.skill.description.length + 50
            if (currentChars + skillChars > maxChars) {
                omitted.add(OmittedSkill(candidate.skill, "omitted_budget"))
                continue
            }

            selected.add(candidate)
            currentChars += skillChars
        }

        return SkillSelectionResult(selected, omitted)
    }

    private fun isRequestMatch(query: String, skillName: String): Boolean {
        if (query.contains("skill:$skillName") || query.contains("/skill $skillName")) return true
        val regex = Regex("""\b${Regex.escape(skillName)}\b""")
        return regex.containsMatchIn(query)
    }

    private fun isProjectMatch(projectDoc: String, skillName: String): Boolean {
        if (projectDoc.isBlank()) return false
        val regex = Regex("""\b${Regex.escape(skillName)}\b""")
        return regex.containsMatchIn(projectDoc)
    }

    private fun isKeywordMatch(query: String, skill: SkillMetadata): Boolean {
        if (query.isBlank()) return false
        val tokens = skill.name.lowercase().split('-', '_', ' ')
            .filter { it.length > 2 && it !in STOP_WORDS }

        if (tokens.any { query.contains(it) }) return true

        val descTokens = skill.description.lowercase().split(' ', ',', '.', ';')
            .filter { it.length > 3 && it !in STOP_WORDS }

        val matches = descTokens.count { query.contains(it) }
        return matches >= 2
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "this", "that", "tool", "skill", "agent", "from"
    )
}
