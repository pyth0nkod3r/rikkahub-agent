package me.rerere.rikkahub.data.ultracode

import me.rerere.rikkahub.data.cartography.ProjectCartographer
import me.rerere.rikkahub.data.dag.ScheduleLimits
import me.rerere.rikkahub.data.dag.TaskDagScheduler
import me.rerere.rikkahub.data.dag.TaskDagValidator
import me.rerere.rikkahub.data.dag.TaskGraph
import me.rerere.rikkahub.data.dag.TaskNode
import me.rerere.rikkahub.data.dag.TaskNodeStatus
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.skills.selector.SkillsSelector
import me.rerere.rikkahub.data.verification.VerificationLedger
import me.rerere.rikkahub.data.verification.VerificationVerdict
import java.io.File
import java.util.UUID

/**
 * UltraCode Autonomous Loop Engine.
 *
 * Implements the 11-stage cycle:
 * UNDERSTAND -> INSPECT -> DISCOVER_SKILLS -> PLAN -> BUILD_DAG ->
 * EXECUTE -> OBSERVE -> ANALYZE -> VERIFY -> (pass => COMPLETE / fail => RE_PLAN)
 */
class UltraCodeEngine(
    val config: UltraCodeConfig = UltraCodeConfig(),
    private val scheduler: TaskDagScheduler = TaskDagScheduler(
        limits = ScheduleLimits(maxWorkers = config.maxWorkers, skipFailedUpstream = config.skipFailedUpstream)
    ),
    private val ledger: VerificationLedger = VerificationLedger.GLOBAL,
    private val cartographer: ProjectCartographer = ProjectCartographer(),
) {
    /**
     * Drives an objective through the full autonomous UltraCode loop.
     */
    suspend fun drive(
        objective: String,
        workspaceId: String? = null,
        workspaceRepository: WorkspaceRepository? = null,
        availableSkills: List<SkillMetadata> = emptyList(),
        initialPlan: List<TaskNode>? = null,
        worker: suspend (TaskNode) -> Result<String>,
        onStageChanged: ((UltraCodeStage) -> Unit)? = null,
    ): UltraCodeRun {
        val runId = UUID.randomUUID().toString()

        // 1. UNDERSTAND
        onStageChanged?.invoke(UltraCodeStage.UNDERSTAND)

        // 2. INSPECT
        onStageChanged?.invoke(UltraCodeStage.INSPECT)
        val projectDoc = if (workspaceId != null && workspaceRepository != null) {
            val dir = workspaceRepository.resolveAbsoluteFile(workspaceId, "/workspace")
            if (dir != null && dir.exists()) {
                val report = cartographer.survey(dir)
                report.generatedMarkdown
            } else ""
        } else ""

        // 3. DISCOVER SKILLS
        onStageChanged?.invoke(UltraCodeStage.DISCOVER_SKILLS)
        val skillSelection = SkillsSelector.select(
            catalog = availableSkills,
            userMessage = objective,
            projectDoc = projectDoc,
        )

        // 4. PLAN & BUILD INITIAL DAG
        onStageChanged?.invoke(UltraCodeStage.PLAN)
        val tasks = if (!initialPlan.isNullOrEmpty()) {
            initialPlan
        } else {
            // Default decomposition if none provided
            listOf(
                TaskNode(
                    id = "step_1",
                    title = "Analyze Requirements and Environment",
                    kind = "computation",
                    needs = emptySet(),
                ),
                TaskNode(
                    id = "step_2",
                    title = "Execute Primary Implementation",
                    kind = "tool",
                    needs = setOf("step_1"),
                ),
                TaskNode(
                    id = "step_3",
                    title = "Verify Outcome and Artifacts",
                    kind = "verification",
                    needs = setOf("step_2"),
                ),
            )
        }

        var graph = TaskGraph(nodes = tasks.associateBy { it.id })
        var cycle = 0

        while (cycle < config.maxCycles) {
            cycle++

            // 5. BUILD DAG & VALIDATE
            onStageChanged?.invoke(UltraCodeStage.BUILD_DAG)
            val validation = TaskDagValidator.validate(graph)
            if (!validation.isValid) {
                return UltraCodeRun(
                    runId = runId,
                    objective = objective,
                    workspaceId = workspaceId,
                    stage = UltraCodeStage.FAILED,
                    graph = graph,
                    cycleCount = cycle,
                    error = "Invalid DAG: ${validation.errors.joinToString("; ")}"
                )
            }

            // 6. EXECUTE
            onStageChanged?.invoke(UltraCodeStage.EXECUTE)
            val executedGraph = scheduler.execute(graph, worker)
            graph = executedGraph

            // 7. OBSERVE & 8. ANALYZE
            onStageChanged?.invoke(UltraCodeStage.OBSERVE)
            onStageChanged?.invoke(UltraCodeStage.ANALYZE)
            val anyFailed = graph.nodes.values.any { it.status == TaskNodeStatus.FAILED }

            // 9. VERIFY
            onStageChanged?.invoke(UltraCodeStage.VERIFY)
            val sessionReport = ledger.verifySession(
                sessionId = runId,
                taskIds = graph.nodes.keys,
            )

            val isDurableOk = sessionReport.overallVerdict != VerificationVerdict.CONTRADICTED

            if (!anyFailed && isDurableOk && graph.isSuccessful()) {
                onStageChanged?.invoke(UltraCodeStage.COMPLETE)
                return UltraCodeRun(
                    runId = runId,
                    objective = objective,
                    workspaceId = workspaceId,
                    stage = UltraCodeStage.COMPLETE,
                    graph = graph,
                    cycleCount = cycle,
                    verificationReport = sessionReport,
                    summary = "Autonomous UltraCode execution completed successfully and verified."
                )
            }

            // Failure or contradiction detected -> 10. RE_PLAN
            if (cycle < config.maxCycles) {
                onStageChanged?.invoke(UltraCodeStage.RE_PLAN)
                // Self-healing: find failed or contradicted tasks and generate repair nodes
                val failedNodes = graph.nodes.values.filter {
                    it.status == TaskNodeStatus.FAILED ||
                            sessionReport.nodeReports[it.id]?.verdict == VerificationVerdict.CONTRADICTED
                }

                var updatedGraph = graph
                for (failed in failedNodes) {
                    val repairId = "repair_${failed.id}_c$cycle"
                    val repairNode = TaskNode(
                        id = repairId,
                        title = "Diagnose and Repair ${failed.title}",
                        kind = "tool",
                        needs = failed.needs,
                        payload = mapOf("targetTaskId" to failed.id, "diagnosis" to (failed.error ?: "Verification contradicted")),
                    )
                    // Add repair node and make downstream nodes depend on the repair instead
                    updatedGraph = updatedGraph.withNode(repairNode)
                    val downstream = updatedGraph.nodes.values.filter { failed.id in it.needs }
                    for (child in downstream) {
                        val resetChild = if (child.status == TaskNodeStatus.SKIPPED) {
                            child.copy(status = TaskNodeStatus.PENDING, error = null, result = null)
                        } else child
                        updatedGraph = updatedGraph.withNode(resetChild)
                            .withoutEdge(failed.id, child.id)
                            .withEdge(repairId, child.id)
                    }
                }

                // Transitively un-skip all downstream nodes whose upstream dependencies are now pending or repairable
                var changedUnskip = true
                while (changedUnskip) {
                    changedUnskip = false
                    for (n in updatedGraph.nodes.values) {
                        if (n.status == TaskNodeStatus.SKIPPED) {
                            val upstreams = n.needs.mapNotNull { updatedGraph.getNode(it) }
                            val allUpstreamRecoverable = upstreams.all { it.status != TaskNodeStatus.FAILED && it.status != TaskNodeStatus.SKIPPED }
                            if (allUpstreamRecoverable && upstreams.isNotEmpty()) {
                                updatedGraph = updatedGraph.withNode(n.copy(status = TaskNodeStatus.PENDING, error = null, result = null))
                                changedUnskip = true
                            }
                        }
                    }
                }

                graph = updatedGraph
            }
        }

        onStageChanged?.invoke(UltraCodeStage.FAILED)
        return UltraCodeRun(
            runId = runId,
            objective = objective,
            workspaceId = workspaceId,
            stage = UltraCodeStage.FAILED,
            graph = graph,
            cycleCount = cycle,
            error = "Exhausted maximum autonomous re-plan cycles ($cycle/${config.maxCycles})"
        )
    }
}
