package me.rerere.rikkahub.data.dag

enum class TaskNodeStatus(val identifier: String) {
    PENDING("pending"),
    READY("ready"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    BLOCKED("blocked"),
    PAUSED("paused"),
    CANCELLED("cancelled"),
    SKIPPED("skipped");

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, FAILED, CANCELLED, SKIPPED)

    companion object {
        fun fromIdentifier(id: String): TaskNodeStatus? =
            entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
    }
}

/**
 * Single node in a Task DAG.
 */
data class TaskNode(
    val id: String,
    val title: String,
    val kind: String = "tool", // "tool", "model", "verification", "approval", "computation"
    val status: TaskNodeStatus = TaskNodeStatus.PENDING,
    val needs: Set<String> = emptySet(), // Pre-requisite node IDs that must be COMPLETED
    val payload: Map<String, String> = emptyMap(),
    val result: String? = null,
    val error: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val priority: Int = 50,
)
