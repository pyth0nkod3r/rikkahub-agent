package me.rerere.rikkahub.data.skills.selector

/**
 * 5 Tiers of skill selection in strict, deterministic precedence order.
 *
 * Precedence: REQUEST -> PROJECT -> ENABLED -> RELEVANT -> GENERAL.
 * A skill takes its earliest matching tier rank, never an additive score.
 */
enum class SkillTier(
    val rank: Int,
    val identifier: String,
    val description: String,
) {
    /** The user's message/request explicitly names or invokes the skill. */
    REQUEST(1, "request", "named in the request"),

    /** The project document (.rikkahub/project.md) or workspace config names the skill. */
    PROJECT(2, "project", "named in project document"),

    /** The user or assistant configuration has explicitly enabled this skill. */
    ENABLED(3, "enabled", "enabled for this conversation/project"),

    /** The skill's keywords, tags, triggers, or description match the user request. */
    RELEVANT(4, "relevant", "matches request keywords"),

    /** General or always-on skill (auto_load: true). Lowest tier, subject to displacement. */
    GENERAL(5, "general", "always on / default");

    companion object {
        val ORDER = entries.sortedBy { it.rank }
    }
}
