package me.rerere.rikkahub.data.ultracode

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.dag.TaskNode
import me.rerere.rikkahub.data.verification.VerificationLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UltraCodeEngineTest {

    private lateinit var ledger: VerificationLedger
    private lateinit var engine: UltraCodeEngine

    @Before
    fun setup() {
        ledger = VerificationLedger()
        engine = UltraCodeEngine(
            ledger = ledger,
        )
    }

    @Test
    fun `ultracode drives complete successful cycle with verification`() = runBlocking {
        val stages = mutableListOf<UltraCodeStage>()

        val plan = listOf(
            TaskNode(id = "prep", title = "Prepare", needs = emptySet()),
            TaskNode(id = "build", title = "Build", needs = setOf("prep")),
        )

        val run = engine.drive(
            objective = "Test clean objective",
            initialPlan = plan,
            worker = { node ->
                // Simulate recorded durable evidence
                ledger.recordCommand(node.id, "run_${node.id}", exitCode = 0)
                Result.success("Done ${node.id}")
            },
            onStageChanged = { stages.add(it) }
        )

        assertEquals(UltraCodeStage.COMPLETE, run.stage)
        assertTrue(stages.contains(UltraCodeStage.UNDERSTAND))
        assertTrue(stages.contains(UltraCodeStage.INSPECT))
        assertTrue(stages.contains(UltraCodeStage.DISCOVER_SKILLS))
        assertTrue(stages.contains(UltraCodeStage.PLAN))
        assertTrue(stages.contains(UltraCodeStage.EXECUTE))
        assertTrue(stages.contains(UltraCodeStage.VERIFY))
        assertTrue(stages.contains(UltraCodeStage.COMPLETE))
        assertNotNull(run.verificationReport)
        assertTrue(run.verificationReport?.ok == true)
    }

    @Test
    fun `ultracode triggers re-planning on failure and heals DAG`() = runBlocking {
        val stages = mutableListOf<UltraCodeStage>()
        var attempts = 0

        val plan = listOf(
            TaskNode(id = "flaky_step", title = "Flaky Step", needs = emptySet(), maxRetries = 0),
        )

        val run = engine.drive(
            objective = "Test self-healing objective",
            initialPlan = plan,
            worker = { node ->
                if (node.id == "flaky_step") {
                    ledger.recordCommand(node.id, "flaky_cmd", exitCode = 1, stderr = "Initial fault")
                    Result.failure(RuntimeException("Initial failure"))
                } else {
                    // Repair node succeeds
                    ledger.recordCommand(node.id, "repair_cmd", exitCode = 0)
                    Result.success("Repaired")
                }
            },
            onStageChanged = { stages.add(it) }
        )

        assertTrue(stages.contains(UltraCodeStage.RE_PLAN))
        assertEquals(UltraCodeStage.COMPLETE, run.stage)
        assertTrue(run.cycleCount >= 2)
        assertTrue(run.graph.nodes.keys.any { it.startsWith("repair_") })
    }

    @Test
    fun `ultracode re-plan resets downstream skipped nodes and executes them`() = runBlocking {
        val plan = listOf(
            TaskNode(id = "step1", title = "Step 1", needs = emptySet(), maxRetries = 0),
            TaskNode(id = "step2", title = "Step 2", needs = setOf("step1")),
        )

        val executedNodes = mutableListOf<String>()

        val run = engine.drive(
            objective = "Test downstream un-skipping",
            initialPlan = plan,
            worker = { node ->
                executedNodes.add(node.id)
                if (node.id == "step1") {
                    ledger.recordCommand(node.id, "step1_cmd", exitCode = 1, stderr = "Fail")
                    Result.failure(RuntimeException("Step 1 Failed"))
                } else {
                    ledger.recordCommand(node.id, "ok_cmd", exitCode = 0)
                    Result.success("Success ${node.id}")
                }
            }
        )

        assertEquals(UltraCodeStage.COMPLETE, run.stage)
        // Step 2 must have executed after step 1 was repaired!
        assertTrue(executedNodes.contains("step2"))
        assertEquals(TaskNodeStatus.COMPLETED, run.graph.getNode("step2")?.status)
    }
}
