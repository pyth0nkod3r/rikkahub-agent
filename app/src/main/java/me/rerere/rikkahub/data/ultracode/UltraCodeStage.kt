package me.rerere.rikkahub.data.ultracode

/**
 * The 11 canonical stages of the UltraCode autonomous loop.
 *
 * Loop: UNDERSTAND -> INSPECT -> DISCOVER_SKILLS -> PLAN -> BUILD_DAG ->
 *       EXECUTE -> OBSERVE -> ANALYZE -> VERIFY -> (pass => COMPLETE / fail => RE_PLAN)
 */
enum class UltraCodeStage(val identifier: String, val description: String) {
    UNDERSTAND("understand", "Analyzing objective, constraints, and requirements"),
    INSPECT("inspect", "Surveying workspace structure and file intelligence"),
    DISCOVER_SKILLS("discover_skills", "Selecting relevant skills via 5-tier selector"),
    PLAN("plan", "Decomposing objective into structured tasks"),
    BUILD_DAG("build_dag", "Constructing and validating execution DAG"),
    EXECUTE("execute", "Executing tasks via bounded DAG scheduler"),
    OBSERVE("observe", "Collecting outputs and command exit codes"),
    ANALYZE("analyze", "Evaluating outcomes and state transitions"),
    VERIFY("verify", "Verifying claims against durable verification ledger"),
    RE_PLAN("re_plan", "Diagnosing failure and updating execution DAG"),
    COMPLETE("complete", "Objective fully achieved and durably confirmed"),
    FAILED("failed", "Autonomous execution exhausted retry budget");

    val isTerminal: Boolean
        get() = this == COMPLETE || this == FAILED

    companion object {
        fun fromIdentifier(id: String): UltraCodeStage? =
            entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
    }
}
