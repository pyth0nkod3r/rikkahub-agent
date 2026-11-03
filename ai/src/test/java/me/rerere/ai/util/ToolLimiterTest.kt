package me.rerere.ai.util

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLimiterTest {

    private fun dummyTool(name: String): Tool = Tool(
        name = name,
        description = "Tool $name",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    @Test
    fun `deduplicates tools with identical names`() {
        val tools = listOf(
            dummyTool("read_file"),
            dummyTool("read_file"),
            dummyTool("search_web"),
        )
        val capped = tools.sanitizeAndCapTools(maxTools = 10)
        assertEquals(2, capped.size)
        assertEquals(listOf("read_file", "search_web"), capped.map { it.name })
    }

    @Test
    fun `prioritizes subagent, workspace, and mcp tools when capping`() {
        val lowPriority = (1..150).map { dummyTool("sensor_tool_$it") }
        val subAgentTools = listOf(
            dummyTool("subagent_dispatch"),
            dummyTool("subagent_list"),
            dummyTool("subagent_get"),
        )
        val mcpTools = (1..10).map { dummyTool("mcp__github__issue_$it") }
        val workspaceTools = listOf(
            dummyTool("workspace_read_file"),
            dummyTool("workspace_shell"),
            dummyTool("search_web"),
            dummyTool("use_skill"),
        )

        val tools = lowPriority + mcpTools + subAgentTools + workspaceTools
        val capped = tools.sanitizeAndCapTools(maxTools = 32)

        assertEquals(32, capped.size)
        val names = capped.map { it.name }.toSet()
        assertTrue(names.contains("subagent_dispatch"))
        assertTrue(names.contains("subagent_list"))
        assertTrue(names.contains("subagent_get"))
        assertTrue(names.contains("workspace_read_file"))
        assertTrue(names.contains("workspace_shell"))
        assertTrue(names.contains("search_web"))
        assertTrue(names.contains("use_skill"))
        mcpTools.forEach { mcp ->
            assertTrue("Expected ${mcp.name} to be retained", names.contains(mcp.name))
        }
    }

    @Test
    fun `resolveEffectiveMaxTools supports default, custom, and unlimited`() {
        assertEquals(MAX_OPENAI_TOOLS, resolveEffectiveMaxTools(null, null))
        assertEquals(64, resolveEffectiveMaxTools(64, null))
        assertEquals(256, resolveEffectiveMaxTools(null, 256))
        assertEquals(32, resolveEffectiveMaxTools(32, 256))
        assertEquals(Int.MAX_VALUE, resolveEffectiveMaxTools(0, 128))
        assertEquals(Int.MAX_VALUE, resolveEffectiveMaxTools(null, 0))
        assertEquals(96, resolveEffectiveMaxTools(null, null, defaultCap = 96))
    }

    @Test
    fun `unlimited cap retains all 264 plus tools without dropping`() {
        val tools = (1..270).map { dummyTool("tool_$it") }
        val unlimited = tools.sanitizeAndCapTools(maxTools = resolveEffectiveMaxTools(0))
        assertEquals(270, unlimited.size)
    }

    @Test
    fun `partitionBySoftCap reserves selector slot and preserves all standby tools`() {
        val tools = (1..264).map { dummyTool("tool_$it") }
        val partition = tools.partitionBySoftCap(
            maxTools = 64,
            pinnedToolNames = setOf("tool_200", "tool_250"),
            demotedToolNames = setOf("tool_1"),
        )
        // 63 active tools + 1 slot reserved for manage_active_tools = 64 soft cap
        assertEquals(63, partition.activeTools.size)
        assertEquals(201, partition.standbyTools.size)
        assertEquals(264, partition.activeTools.size + partition.standbyTools.size)

        val activeNames = partition.activeTools.map { it.name }.toSet()
        val standbyNames = partition.standbyTools.map { it.name }.toSet()
        assertTrue(activeNames.contains("tool_200"))
        assertTrue(activeNames.contains("tool_250"))
        assertTrue(standbyNames.contains("tool_1"))
    }
}