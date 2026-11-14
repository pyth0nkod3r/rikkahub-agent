package me.rerere.rikkahub.data.ai.broker

/**
 * Functional interface for a single, fault-isolated context collector.
 *
 * Each collector gathers its specific slice of context. Failures inside a collector
 * are intercepted and logged to [ContextBundle.errors] and will never break prompt assembly
 * or suppress critical rules/memories.
 */
fun interface ContextCollector {
    suspend fun collect(request: ContextRequest): ContextItem?
}
