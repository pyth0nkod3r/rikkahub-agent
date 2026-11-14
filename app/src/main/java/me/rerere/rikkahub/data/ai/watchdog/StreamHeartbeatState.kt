package me.rerere.rikkahub.data.ai.watchdog

/**
 * Live stream activity heartbeat metrics and interactive stuck resolution state.
 *
 * @param elapsedSeconds Total elapsed duration since the generation stream started.
 * @param idleSeconds Seconds since the last chunk was received from the model.
 * @param isStuck True when no chunks have arrived for longer than the stuck threshold (default 20s).
 * @param canSwitchModel True if an alternate fallback/secondary model is available to cascade to.
 * @param candidateModelName Display name of the candidate secondary model if available.
 * @param statusText Human-readable heartbeat summary for UI display.
 */
data class StreamHeartbeatState(
    val elapsedSeconds: Long = 0L,
    val idleSeconds: Long = 0L,
    val isStuck: Boolean = false,
    val canSwitchModel: Boolean = false,
    val candidateModelName: String? = null,
    val statusText: String? = null,
)
