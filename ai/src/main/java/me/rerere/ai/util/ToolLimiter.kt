package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.core.Tool

private const val TAG = "ToolLimiter"
const val MAX_OPENAI_TOOLS = 128

private val HIGH_PRIORITY_TOOL_NAMES = setOf(
    "propose_plan",
    "search_web",
    "scrape_web",
    "save_memory",
    "use_skill",
    "ask_user",
    "run_command",
    "read_file",
    "edit_file",
    "write_file",
    "list_files",
    "file_editor",
    "view_file",
)

/**
 * Deduplicates and caps tool definitions to stay within OpenAI and third-party gateway
 * hard tool limits (typically 128 functions).
 *
 * When tool count exceeds [maxTools], tools are prioritized:
 * 1. Essential orchestration, planning, search, skills, and workspace file/terminal tools
 * 2. User-enabled MCP tools (mcp__*)
 * 3. Core local device tools (clipboard, time, tts)
 * 4. Other device / system / sensor tools
 */
fun List<Tool>.sanitizeAndCapTools(maxTools: Int = MAX_OPENAI_TOOLS): List<Tool> {
    val deduplicated = this.distinctBy { it.name }
    if (deduplicated.size <= maxTools) {
        return deduplicated
    }

    try {
        Log.w(
            TAG,
            "Tool count (${deduplicated.size}) exceeds limit ($maxTools). Prioritizing and capping to $maxTools tools."
        )
    } catch (_: Throwable) {
        // android.util.Log may not be mocked in pure JVM tests
    }

    val p1 = deduplicated.filter {
        it.name in HIGH_PRIORITY_TOOL_NAMES || it.name.startsWith("workspace_")
    }
    val p2 = deduplicated.filter { it.name.startsWith("mcp__") && it !in p1 }
    val p3 = deduplicated.filter {
        it.name in setOf("clipboard_tool", "get_time_info", "text_to_speech") && it !in p1 && it !in p2
    }
    val p4 = deduplicated.filter { it !in p1 && it !in p2 && it !in p3 }

    val prioritized = (p1 + p2 + p3 + p4).distinctBy { it.name }
    return prioritized.take(maxTools)
}
