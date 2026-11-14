package me.rerere.rikkahub.data.dag

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.rikkahub.data.verification.VerificationLedger

data class ScheduleLimits(
    val maxWorkers: Int = 4,
    val maxRunning: Int = 4,
    val skipFailedUpstream: Boolean = true,
)

data class SchedulePlan(
    val dispatchableNodes: List<TaskNode>,
    val holdReasons: Map<String, String>, // nodeId -> reason code
)

/**
 * Task DAG Dispatcher and Execution Pump.
 *
 * Implements "Never blindly run every READY node":
 * Respects concurrency limits, dependency settlement, and upstream failure cascading.
 */
class TaskDagScheduler(
    val limits: ScheduleLimits = ScheduleLimits(),
    private val ledger: VerificationLedger = VerificationLedger.GLOBAL,
) {
    /**
     * Determines which nodes may start right now, and records why the rest may not.
     */
    fun planNext(graph: TaskGraph): SchedulePlan {
        val holdReasons = mutableMapOf<String, String>()
        val readyCandidates = mutableListOf<TaskNode>()

        val runningCount = graph.nodes.values.count { it.status == TaskNodeStatus.RUNNING }
        val availableSlots = (limits.maxWorkers - runningCount).coerceAtLeast(0)

        for (node in graph.nodes.values) {
            if (node.status.isTerminal || node.status == TaskNodeStatus.RUNNING || node.status == TaskNodeStatus.PAUSED) {
                continue
            }

            // Check dependencies
            val missingDep = node.needs.firstOrNull { graph.getNode(it) == null }
            if (missingDep != null) {
                holdReasons[node.id] = "missing_dependency"
                continue
            }

            val deps = node.needs.mapNotNull { graph.getNode(it) }
            val failedDep = deps.firstOrNull { it.status in setOf(TaskNodeStatus.FAILED, TaskNodeStatus.CANCELLED, TaskNodeStatus.SKIPPED) }
            val pendingDep = deps.firstOrNull { it.status != TaskNodeStatus.COMPLETED }

            when {
                failedDep != null -> {
                    holdReasons[node.id] = "upstream_failed"
                }
                pendingDep != null -> {
                    holdReasons[node.id] = "needs_pending"
                }
                else -> {
                    readyCandidates.add(node)
                }
            }
        }

        // Sort candidates by priority descending, then title
        val sorted = readyCandidates.sortedWith(
            compareByDescending<TaskNode> { it.priority }.thenBy { it.title }
        )

        val dispatchable = sorted.take(availableSlots)
        for (held in sorted.drop(availableSlots)) {
            holdReasons[held.id] = "worker_limit"
        }

        return SchedulePlan(dispatchable, holdReasons)
    }

    /**
     * Executes the graph to settlement using the provided worker coroutine.
     */
    suspend fun execute(
        initialGraph: TaskGraph,
        worker: suspend (TaskNode) -> Result<String>,
        onNodeUpdated: ((TaskNode) -> Unit)? = null,
    ): TaskGraph = coroutineScope {
        var currentGraph = initialGraph

        // Cascade skips for nodes whose upstreams are already failed
        if (limits.skipFailedUpstream) {
            currentGraph = cascadeUpstreamFailures(currentGraph, onNodeUpdated)
        }

        while (!currentGraph.isAllTerminal()) {
            val plan = planNext(currentGraph)

            // Cascade skips for any newly held with upstream_failed
            if (limits.skipFailedUpstream && plan.holdReasons.any { it.value == "upstream_failed" }) {
                currentGraph = cascadeUpstreamFailures(currentGraph, onNodeUpdated)
                continue
            }

            if (plan.dispatchableNodes.isEmpty()) {
                // If there are no dispatchable nodes and no running nodes, graph is deadlocked
                val hasRunning = currentGraph.nodes.values.any { it.status == TaskNodeStatus.RUNNING }
                if (!hasRunning) {
                    // Mark all remaining un-started nodes as BLOCKED
                    currentGraph = currentGraph.copy(
                        nodes = currentGraph.nodes.mapValues { (_, node) ->
                            if (!node.status.isTerminal) {
                                val blocked = node.copy(status = TaskNodeStatus.BLOCKED, error = "Graph stalled without dispatchable tasks")
                                onNodeUpdated?.invoke(blocked)
                                blocked
                            } else node
                        }
                    )
                    break
                }
            }

            // Mark dispatched nodes as RUNNING
            val toRun = plan.dispatchableNodes
            for (node in toRun) {
                val running = node.copy(status = TaskNodeStatus.RUNNING)
                currentGraph = currentGraph.withNode(running)
                ledger.recordTaskClaim(node.id, TaskNodeStatus.RUNNING.identifier)
                onNodeUpdated?.invoke(running)
            }

            // Execute concurrently
            val outcomes = toRun.map { node ->
                async {
                    val result = runCatching { worker(node) }.getOrElse { Result.failure(it) }
                    node to result
                }
            }.awaitAll()

            // Update finished nodes
            for ((node, outcome) in outcomes) {
                outcome.fold(
                    onSuccess = { res ->
                        val completed = node.copy(
                            status = TaskNodeStatus.COMPLETED,
                            result = res,
                            error = null,
                        )
                        currentGraph = currentGraph.withNode(completed)
                        ledger.recordTaskClaim(node.id, TaskNodeStatus.COMPLETED.identifier)
                        onNodeUpdated?.invoke(completed)
                    },
                    onFailure = { err ->
                        if (node.retryCount < node.maxRetries) {
                            // Retry
                            val retry = node.copy(
                                status = TaskNodeStatus.PENDING,
                                retryCount = node.retryCount + 1,
                                error = "Retry ${node.retryCount + 1}/${node.maxRetries}: ${err.message}",
                            )
                            currentGraph = currentGraph.withNode(retry)
                            onNodeUpdated?.invoke(retry)
                        } else {
                            val failed = node.copy(
                                status = TaskNodeStatus.FAILED,
                                error = err.message ?: "Task failed",
                            )
                            currentGraph = currentGraph.withNode(failed)
                            ledger.recordTaskClaim(node.id, TaskNodeStatus.FAILED.identifier, failed.error)
                            onNodeUpdated?.invoke(failed)
                        }
                    }
                )
            }

            if (limits.skipFailedUpstream) {
                currentGraph = cascadeUpstreamFailures(currentGraph, onNodeUpdated)
            }
        }

        currentGraph
    }

    private fun cascadeUpstreamFailures(
        graph: TaskGraph,
        onNodeUpdated: ((TaskNode) -> Unit)? = null,
    ): TaskGraph {
        var updated = graph
        var changed = true

        while (changed) {
            changed = false
            for (node in updated.nodes.values) {
                if (node.status.isTerminal) continue
                val hasFailedDep = node.needs.any { prereqId ->
                    val prereq = updated.getNode(prereqId)
                    prereq != null && (prereq.status == TaskNodeStatus.FAILED || prereq.status == TaskNodeStatus.SKIPPED || prereq.status == TaskNodeStatus.CANCELLED)
                }
                if (hasFailedDep) {
                    val skipped = node.copy(
                        status = TaskNodeStatus.SKIPPED,
                        error = "Skipped because upstream dependency failed"
                    )
                    updated = updated.withNode(skipped)
                    ledger.recordTaskClaim(node.id, TaskNodeStatus.SKIPPED.identifier, skipped.error)
                    onNodeUpdated?.invoke(skipped)
                    changed = true
                }
            }
        }

        return updated
    }
}
