package me.rerere.ai.util

import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLimiterTest {

    private fun createDummyTool(name: String) = Tool(
        name = name,
        description = "Dummy tool $name",
        execute = { emptyList() },
    )

    @Test
    fun `tools within limit are deduplicated and preserved in order`() {
        val tools = listOf(
            createDummyTool("search_web"),
            createDummyTool("search_web"),
            createDummyTool("propose_plan"),
            createDummyTool("custom_tool"),
        )
        val capped = tools.sanitizeAndCapTools(maxTools = 10)
        assertEquals(3, capped.size)
        assertEquals("search_web", capped[0].name)
        assertEquals("propose_plan", capped[1].name)
        assertEquals("custom_tool", capped[2].name)
    }

    @Test
    fun `tools exceeding limit are prioritized and capped to maxTools`() {
        val tools = mutableListOf<Tool>()
        // 100 low priority sensor/hardware tools
        for (i in 1..100) {
            tools.add(createDummyTool("sensor_$i"))
        }
        // 30 MCP tools
        for (i in 1..30) {
            tools.add(createDummyTool("mcp__server__tool_$i"))
        }
        // 5 workspace and core tools
        tools.add(createDummyTool("run_command"))
        tools.add(createDummyTool("read_file"))
        tools.add(createDummyTool("propose_plan"))
        tools.add(createDummyTool("search_web"))
        tools.add(createDummyTool("save_memory"))

        val capped = tools.sanitizeAndCapTools(maxTools = 32)
        assertEquals(32, capped.size)

        // Verify highest priority tools survived
        val cappedNames = capped.map { it.name }.toSet()
        assertTrue("propose_plan survived", cappedNames.contains("propose_plan"))
        assertTrue("run_command survived", cappedNames.contains("run_command"))
        assertTrue("read_file survived", cappedNames.contains("read_file"))
        assertTrue("search_web survived", cappedNames.contains("search_web"))
        assertTrue("save_memory survived", cappedNames.contains("save_memory"))

        // Verify MCP tools survived before lower priority sensors
        val mcpCount = capped.count { it.name.startsWith("mcp__") }
        assertTrue("MCP tools prioritized over generic sensors", mcpCount > 0)
    }
}
