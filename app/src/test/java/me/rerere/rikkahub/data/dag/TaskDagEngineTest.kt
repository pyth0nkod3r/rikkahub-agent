package me.rerere.rikkahub.data.dag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDagEngineTest {

    @Test
    fun `validator detects missing dependency`() {
        val nodeA = TaskNode(id = "A", title = "Task A", needs = setOf("NON_EXISTENT"))
        val graph = TaskGraph(nodes = mapOf("A" to nodeA))

        val result = TaskDagValidator.validate(graph)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("NON_EXISTENT") })
    }

    @Test
    fun `validator detects cycle in dependencies`() {
        val nodeA = TaskNode(id = "A", title = "Task A", needs = setOf("B"))
        val nodeB = TaskNode(id = "B", title = "Task B", needs = setOf("A"))
        val graph = TaskGraph(nodes = mapOf("A" to nodeA, "B" to nodeB))

        val result = TaskDagValidator.validate(graph)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Cycle detected") })
    }

    @Test
    fun `validator computes correct topological order and execution levels`() {
        // A -> B -> D
        //   -> C -> D
        val nodeA = TaskNode(id = "A", title = "Task A", needs = emptySet())
        val nodeB = TaskNode(id = "B", title = "Task B", needs = setOf("A"))
        val nodeC = TaskNode(id = "C", title = "Task C", needs = setOf("A"))
        val nodeD = TaskNode(id = "D", title = "Task D", needs = setOf("B", "C"))

        val graph = TaskGraph(nodes = mapOf("A" to nodeA, "B" to nodeB, "C" to nodeC, "D" to nodeD))
        val result = TaskDagValidator.validate(graph)

        assertTrue(result.isValid)
        assertEquals(listOf("A"), result.executionLevels[0])
        assertEquals(listOf("B", "C"), result.executionLevels[1].sorted())
        assertEquals(listOf("D"), result.executionLevels[2])
    }

    @Test
    fun `scheduler executes tasks in order and respects dependencies`() = runBlocking {
        val nodeA = TaskNode(id = "A", title = "Task A", needs = emptySet())
        val nodeB = TaskNode(id = "B", title = "Task B", needs = setOf("A"))

        val graph = TaskGraph(nodes = mapOf("A" to nodeA, "B" to nodeB))
        val scheduler = TaskDagScheduler(limits = ScheduleLimits(maxWorkers = 2))

        val executionOrder = mutableListOf<String>()
        val finalGraph = scheduler.execute(graph) { task ->
            executionOrder.add(task.id)
            Result.success("Done ${task.id}")
        }

        assertTrue(finalGraph.isSuccessful())
        assertEquals(listOf("A", "B"), executionOrder)
    }

    @Test
    fun `scheduler cascades skips when upstream task fails`() = runBlocking {
        val nodeA = TaskNode(id = "A", title = "Task A", needs = emptySet(), maxRetries = 0)
        val nodeB = TaskNode(id = "B", title = "Task B", needs = setOf("A"))

        val graph = TaskGraph(nodes = mapOf("A" to nodeA, "B" to nodeB))
        val scheduler = TaskDagScheduler(limits = ScheduleLimits(skipFailedUpstream = true))

        val finalGraph = scheduler.execute(graph) { task ->
            if (task.id == "A") {
                Result.failure(RuntimeException("Fatal error in A"))
            } else {
                Result.success("Success")
            }
        }

        assertEquals(TaskNodeStatus.FAILED, finalGraph.getNode("A")?.status)
        assertEquals(TaskNodeStatus.SKIPPED, finalGraph.getNode("B")?.status)
    }

    @Test
    fun `scheduler planNext holds task with missing dependency`() {
        val nodeA = TaskNode(id = "A", title = "Task A", needs = setOf("GHOST_DEP"))
        val graph = TaskGraph(nodes = mapOf("A" to nodeA))
        val scheduler = TaskDagScheduler()

        val plan = scheduler.planNext(graph)
        assertTrue(plan.dispatchableNodes.isEmpty())
        assertEquals("missing_dependency", plan.holdReasons["A"])
    }
}
