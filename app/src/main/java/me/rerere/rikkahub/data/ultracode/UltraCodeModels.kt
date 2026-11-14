package me.rerere.rikkahub.data.ultracode

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.dag.TaskGraph
import me.rerere.rikkahub.data.verification.SessionVerificationReport

data class UltraCodeConfig(
    val maxCycles: Int = 5,
    val maxWorkers: Int = 4,
    val skipFailedUpstream: Boolean = true,
    val enforceVerification: Boolean = true,
)

data class UltraCodeRun(
    val runId: String,
    val objective: String,
    val workspaceId: String?,
    val stage: UltraCodeStage,
    val graph: TaskGraph,
    val cycleCount: Int = 0,
    val verificationReport: SessionVerificationReport? = null,
    val summary: String? = null,
    val error: String? = null,
) {
    fun toJsonString(): String = buildJsonObject {
        put("runId", runId)
        put("objective", objective)
        put("stage", stage.identifier)
        put("cycleCount", cycleCount)
        put("totalNodes", graph.nodes.size)
        put("completedNodes", graph.nodes.values.count { it.status.identifier == "completed" })
        put("failedNodes", graph.nodes.values.count { it.status.identifier == "failed" })
        put("skippedNodes", graph.nodes.values.count { it.status.identifier == "skipped" })
        if (summary != null) put("summary", summary)
        if (error != null) put("error", error)
        if (verificationReport != null) {
            put("verificationVerdict", verificationReport.overallVerdict.code)
            put("isVerified", verificationReport.ok)
        }
        put("nodes", buildJsonArray {
            graph.nodes.values.forEach { node ->
                add(buildJsonObject {
                    put("id", node.id)
                    put("title", node.title)
                    put("status", node.status.identifier)
                    if (node.error != null) put("error", node.error)
                })
            }
        })
    }.toString()
}
