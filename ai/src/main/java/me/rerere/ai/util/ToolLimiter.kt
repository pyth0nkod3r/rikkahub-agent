package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.core.Tool

private const val TAG = "ToolLimiter"
const val MAX_OPENAI_TOOLS = 128
const val UNLIMITED_TOOLS = 0
const val MANAGE_ACTIVE_TOOLS_NAME = "manage_active_tools"

val HIGH_PRIORITY_TOOL_NAMES = setOf(
    MANAGE_ACTIVE_TOOLS_NAME,
    "subagent_dispatch",
    "subagent_list",
    "subagent_get",
    "subagent_cancel",
    "subagent_reinject",
    "propose_plan",
    "search_web",
    "scrape_web",
    "web_fetch",
    "web_extract",
    "save_memory",
    "memory_read",
    "memory_write",
    "memory_list",
    "use_skill",
    "skill_get_content",
    "ask_user",
    "run_command",
    "read_file",
    "edit_file",
    "write_file",
    "list_files",
    "file_editor",
    "view_file",
    "termux_run_command",
    "ssh_exec",
)

private val CORE_LOCAL_TOOL_NAMES = setOf(
    "clipboard_tool",
    "get_time_info",
    "text_to_speech",
    "eval_javascript",
)

data class ToolSoftCapPartition(
    val activeTools: List<Tool>,
    val standbyTools: List<Tool>,
    val effectiveCap: Int,
)

/**
 * Resolves the effective soft tool cap from per-model and per-assistant settings.
 * - `null` falls back to the next tier or [MAX_OPENAI_TOOLS] (128).
 * - `<= 0` means Unlimited ([Int.MAX_VALUE]), allowing all 264+ tools to be sent directly.
 */
fun resolveEffectiveMaxTools(
    modelMaxTools: Int?,
    assistantMaxTools: Int? = null,
): Int {
    val configured = modelMaxTools ?: assistantMaxTools ?: MAX_OPENAI_TOOLS
    return if (configured <= 0) Int.MAX_VALUE else configured
}

/**
 * Orders tools by priority without dropping any tool:
 * 0. [MANAGE_ACTIVE_TOOLS_NAME] and user/dynamically pinned tools ([pinnedToolNames])
 * 1. Sub-agent orchestration, planning, search, memory, skills, workspace, and shell tools
 * 2. Enabled MCP tools (`mcp__*`)
 * 3. Core local utilities (clipboard, time, tts, javascript)
 * 4. Remaining non-demoted device/system tools
 * 5. Explicitly demoted tools ([demotedToolNames])
 */
fun List<Tool>.prioritizeTools(
    pinnedToolNames: Set<String> = emptySet(),
    demotedToolNames: Set<String> = emptySet(),
): List<Tool> {
    val deduplicated = this.distinctBy { it.name }
    if (deduplicated.size <= 1) return deduplicated

    val nonDemoted = deduplicated.filter { it.name !in demotedToolNames || it.name == MANAGE_ACTIVE_TOOLS_NAME }
    val demoted = deduplicated.filter { it.name in demotedToolNames && it.name != MANAGE_ACTIVE_TOOLS_NAME }

    val p0 = nonDemoted.filter {
        it.name == MANAGE_ACTIVE_TOOLS_NAME || it.name in pinnedToolNames
    }
    val p1 = nonDemoted.filter {
        it !in p0 && (it.name in HIGH_PRIORITY_TOOL_NAMES || it.name.startsWith("workspace_"))
    }
    val p2 = nonDemoted.filter {
        it !in p0 && it !in p1 && it.name.startsWith("mcp__")
    }
    val p3 = nonDemoted.filter {
        it !in p0 && it !in p1 && it !in p2 && it.name in CORE_LOCAL_TOOL_NAMES
    }
    val p4 = nonDemoted.filter {
        it !in p0 && it !in p1 && it !in p2 && it !in p3
    }

    return (p0 + p1 + p2 + p3 + p4 + demoted).distinctBy { it.name }
}

/**
 * Partitions tools into an active window and a standby pool according to a modifiable soft cap.
 * Standby tools are not discarded: when [standbyTools] is non-empty, 1 slot is reserved in the
 * active window (when [reserveSlotForSelector] is true and [maxTools] >= 2) so a dynamic selector
 * tool (`manage_active_tools`) can expose and invoke or enable any standby tool on demand.
 */
fun List<Tool>.partitionBySoftCap(
    maxTools: Int = MAX_OPENAI_TOOLS,
    pinnedToolNames: Set<String> = emptySet(),
    demotedToolNames: Set<String> = emptySet(),
    reserveSlotForSelector: Boolean = true,
): ToolSoftCapPartition {
    val effectiveCap = if (maxTools <= 0) Int.MAX_VALUE else maxTools
    val deduplicated = this.distinctBy { it.name }
    val activeDemotions = demotedToolNames.intersect(deduplicated.map { it.name }.toSet()) - MANAGE_ACTIVE_TOOLS_NAME

    if (deduplicated.size <= effectiveCap && activeDemotions.isEmpty()) {
        return ToolSoftCapPartition(
            activeTools = deduplicated,
            standbyTools = emptyList(),
            effectiveCap = effectiveCap,
        )
    }

    val prioritized = deduplicated.prioritizeTools(
        pinnedToolNames = pinnedToolNames,
        demotedToolNames = activeDemotions,
    )
    val nonDemotedCount = prioritized.count { it.name !in activeDemotions }
    val hasSelectorAlready = prioritized.any { it.name == MANAGE_ACTIVE_TOOLS_NAME }
    val activeWindowLimit = if (reserveSlotForSelector && !hasSelectorAlready && effectiveCap in 2 until Int.MAX_VALUE) {
        effectiveCap - 1
    } else {
        effectiveCap
    }
    val takeCount = minOf(activeWindowLimit, nonDemotedCount)
    val active = prioritized.take(takeCount)
    val activeNames = active.map { it.name }.toSet()
    val standby = deduplicated.filter { it.name !in activeNames }

    return ToolSoftCapPartition(
        activeTools = active,
        standbyTools = standby,
        effectiveCap = effectiveCap,
    )
}

/**
 * Deduplicates and caps tool definitions to stay within the configured soft cap.
 * Passing `maxTools <= 0` or `Int.MAX_VALUE` disables capping (unlimited tools).
 */
fun List<Tool>.sanitizeAndCapTools(maxTools: Int = MAX_OPENAI_TOOLS): List<Tool> {
    val deduplicated = this.distinctBy { it.name }
    if (maxTools <= 0 || maxTools == Int.MAX_VALUE || deduplicated.size <= maxTools) {
        return deduplicated
    }

    try {
        Log.w(
            TAG,
            "Tool count (${deduplicated.size}) exceeds soft cap ($maxTools). Prioritizing active window to $maxTools tools."
        )
    } catch (_: Throwable) {
        // android.util.Log may not be mocked in pure JVM tests
    }

    return deduplicated.prioritizeTools().take(maxTools)
}