package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.util.MANAGE_ACTIVE_TOOLS_NAME
import me.rerere.ai.util.ToolSoftCapPartition
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.subagent.SubAgentToolPolicies
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Per-conversation dynamic tool selection state. Allows the LLM, sub-agents, or the user to
 * promote standby tools into the active window or demote active tools to standby when the
 * total tool count exceeds the configured soft cap.
 */
private val selectorJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

object ConversationToolSelectionState {
    data class Selection(
        val enabled: Set<String> = emptySet(),
        val disabled: Set<String> = emptySet(),
    ) {
        fun hasOverrides(): Boolean = enabled.isNotEmpty() || disabled.isNotEmpty()
    }

    private val defaultKey = Uuid.parse("00000000-0000-0000-0000-000000000000")
    private val byConversation = ConcurrentHashMap<Uuid, Selection>()

    fun get(conversationId: Uuid?): Selection =
        byConversation[conversationId ?: defaultKey] ?: Selection()

    fun enable(conversationId: Uuid?, toolNames: Collection<String>): Selection {
        val key = conversationId ?: defaultKey
        val cleaned = toolNames.map { it.trim() }.filter { it.isNotEmpty() && it != MANAGE_ACTIVE_TOOLS_NAME }.toSet()
        if (cleaned.isEmpty()) return get(conversationId)
        return byConversation.compute(key) { _, current ->
            val base = current ?: Selection()
            base.copy(
                enabled = base.enabled + cleaned,
                disabled = base.disabled - cleaned,
            )
        }!!
    }

    fun disable(conversationId: Uuid?, toolNames: Collection<String>): Selection {
        val key = conversationId ?: defaultKey
        val cleaned = toolNames.map { it.trim() }.filter { it.isNotEmpty() && it != MANAGE_ACTIVE_TOOLS_NAME }.toSet()
        if (cleaned.isEmpty()) return get(conversationId)
        return byConversation.compute(key) { _, current ->
            val base = current ?: Selection()
            base.copy(
                enabled = base.enabled - cleaned,
                disabled = base.disabled + cleaned,
            )
        }!!
    }

    fun reset(conversationId: Uuid?) {
        byConversation.remove(conversationId ?: defaultKey)
    }
}

internal data class DelegatedToolInvocation(
    val toolName: String,
    val arguments: JsonObject,
)

internal fun parseManageActiveToolsInvocation(input: JsonElement): DelegatedToolInvocation? {
    val obj = runCatching { input.jsonObject }.getOrNull() ?: return null
    val action = obj["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
    if (action != "invoke" && action != "call" && action != "run") return null
    val targetName = obj["tool_name"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: obj["tool_names"]?.let { runCatching { it.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull?.trim() }.getOrNull() }
        ?: return null
    if (targetName.isEmpty() || targetName == MANAGE_ACTIVE_TOOLS_NAME) return null
    val argsObj = runCatching { obj["arguments"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    return DelegatedToolInvocation(toolName = targetName, arguments = argsObj)
}

internal fun parseManageActiveToolsInvocationFromRaw(rawInput: String): DelegatedToolInvocation? {
    val parsed = runCatching { selectorJson.parseToJsonElement(rawInput.ifBlank { "{}" }) }.getOrNull() ?: return null
    return parseManageActiveToolsInvocation(parsed)
}

/**
 * Builds the `manage_active_tools` meta-tool when a conversation's available tools exceed
 * the active soft cap (or when dynamic overrides are active). Ensures that standby tools are
 * never lost: they remain discoverable, selectable into the active window, and directly invocable.
 */
fun createManageActiveToolsTool(
    conversationId: Uuid?,
    allTools: List<Tool>,
    partition: ToolSoftCapPartition,
): Tool {
    val standbyNames = partition.standbyTools.map { it.name }
    val standbyPreview = if (standbyNames.isEmpty()) {
        "none (all tools active)"
    } else if (standbyNames.size <= 60) {
        standbyNames.joinToString(", ")
    } else {
        standbyNames.take(60).joinToString(", ") + ", ... (+${standbyNames.size - 60} more; call with action=\"list\" to view all)"
    }

    val description = buildString {
        append(
            "Select (enable/disable), inspect, or invoke tools beyond the active soft cap " +
                "(${partition.activeTools.size}/${allTools.size} active, soft cap=${partition.effectiveCap}). "
        )
        if (standbyNames.isNotEmpty()) {
            append("${standbyNames.size} standby tools are available on demand: [$standbyPreview]. ")
        }
        append(
            "Use action=\"enable\" with tool_names=[...] to bring standby tools into the active window for the next step, " +
                "action=\"disable\" to demote active tools, action=\"inspect\" to view full JSON schemas, " +
                "action=\"list\" to search all tools, or action=\"invoke\" with tool_name and arguments to run a standby tool directly."
        )
    }

    return Tool(
        name = MANAGE_ACTIVE_TOOLS_NAME,
        description = description,
        systemPrompt = { _, _ ->
            if (standbyNames.isEmpty()) {
                ""
            } else {
                buildString {
                    appendLine("[Dynamic Tool Soft Cap Active: ${partition.activeTools.size}/${allTools.size} tools in active window (cap=${partition.effectiveCap}), ${standbyNames.size} tools in standby]")
                    appendLine("All ${allTools.size} tools remain usable. If you need a tool not currently in the active window, call `$MANAGE_ACTIVE_TOOLS_NAME` with:")
                    appendLine("- `{\"action\": \"enable\", \"tool_names\": [\"<name>\"]}` to activate it into the window and inspect its parameters, or")
                    appendLine("- `{\"action\": \"invoke\", \"tool_name\": \"<name>\", \"arguments\": {...}}` to execute it immediately.")
                    append("Standby tools: $standbyPreview")
                }
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("enable"))
                            add(JsonPrimitive("disable"))
                            add(JsonPrimitive("list"))
                            add(JsonPrimitive("inspect"))
                            add(JsonPrimitive("invoke"))
                        })
                        put(
                            "description",
                            "Operation to perform: 'enable' (activate standby tools into active window), " +
                                "'disable' (demote tools to standby), 'list' (search/list tools), " +
                                "'inspect' (get full parameter schemas), or 'invoke' (execute a tool directly)."
                        )
                    })
                    put("tool_names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Tool names to enable, disable, or inspect.")
                    })
                    put("disable_tool_names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional tool names to demote to standby when enabling other tools.")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional keyword filter when listing or enabling tools by category/keyword.")
                    })
                    put("tool_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Target tool name when action is 'invoke' (or single-tool shorthand for enable/disable/inspect).")
                    })
                    put("arguments", buildJsonObject {
                        put("type", "object")
                        put("description", "JSON object arguments to pass to tool_name when action is 'invoke'.")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input ->
            val delegated = parseManageActiveToolsInvocation(input)
            if (delegated == null) {
                false
            } else {
                val targetTool = allTools.find { it.name == delegated.toolName }
                targetTool?.needsApproval(delegated.arguments) == true ||
                    ToolApprovalDefaults.requiresApproval(delegated.toolName)
            }
        },
        execute = { input ->
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                ?: "list"
            val requestedNames = buildList {
                params["tool_name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                params["tool_names"]?.let { el ->
                    if (el is JsonArray) {
                        el.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }.forEach(::add)
                    }
                }
            }.distinct()
            val disableNames = params["disable_tool_names"]?.let { el ->
                if (el is JsonArray) {
                    el.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
                } else emptyList()
            }.orEmpty()
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

            val activeNameSet = partition.activeTools.map { it.name }.toSet()

            when (action) {
                "enable", "select" -> {
                    val matchedByQuery = if (query.isNotBlank() && requestedNames.isEmpty()) {
                        allTools.filter {
                            it.name.contains(query, ignoreCase = true) ||
                                it.description.contains(query, ignoreCase = true)
                        }.map { it.name }
                    } else emptyList()
                    val targetNames = (requestedNames + matchedByQuery)
                        .filter { name -> allTools.any { it.name == name } }
                        .distinct()
                    val unknownNames = requestedNames.filter { name -> allTools.none { it.name == name } }

                    if (disableNames.isNotEmpty()) {
                        ConversationToolSelectionState.disable(conversationId, disableNames)
                    }
                    val updatedSelection = ConversationToolSelectionState.enable(conversationId, targetNames)
                    val enabledSchemas = buildJsonObject {
                        targetNames.mapNotNull { name -> allTools.find { it.name == name } }.forEach { tool ->
                            put(tool.name, buildJsonObject {
                                put("description", JsonPrimitive(tool.description))
                                val schema = tool.parameters()
                                if (schema != null) {
                                    put("parameters", selectorJson.encodeToJsonElement(schema))
                                }
                            })
                        }
                    }
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("status", JsonPrimitive("ok"))
                                put("enabled", buildJsonArray { targetNames.forEach { add(JsonPrimitive(it)) } })
                                if (disableNames.isNotEmpty()) {
                                    put("disabled", buildJsonArray { disableNames.forEach { add(JsonPrimitive(it)) } })
                                }
                                if (unknownNames.isNotEmpty()) {
                                    put("unknown_tools", buildJsonArray { unknownNames.forEach { add(JsonPrimitive(it)) } })
                                }
                                put("pinned_in_conversation", buildJsonArray { updatedSelection.enabled.forEach { add(JsonPrimitive(it)) } })
                                put("schemas", enabledSchemas)
                                put(
                                    "message",
                                    JsonPrimitive(
                                        if (targetNames.isEmpty()) "No matching tools found to enable."
                                        else "Enabled ${targetNames.joinToString(", ")} into the active tool window for subsequent steps."
                                    )
                                )
                            }.toString()
                        )
                    )
                }

                "disable" -> {
                    val targets = (requestedNames + disableNames).distinct()
                    val updatedSelection = ConversationToolSelectionState.disable(conversationId, targets)
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("status", JsonPrimitive("ok"))
                                put("disabled", buildJsonArray { targets.forEach { add(JsonPrimitive(it)) } })
                                put("demoted_in_conversation", buildJsonArray { updatedSelection.disabled.forEach { add(JsonPrimitive(it)) } })
                                put("message", JsonPrimitive("Demoted ${targets.joinToString(", ")} to standby."))
                            }.toString()
                        )
                    )
                }

                "inspect" -> {
                    val targets = if (requestedNames.isNotEmpty()) {
                        allTools.filter { it.name in requestedNames }
                    } else if (query.isNotBlank()) {
                        allTools.filter { it.name.contains(query, ignoreCase = true) }.take(20)
                    } else {
                        partition.standbyTools.take(15)
                    }
                    val schemas = buildJsonObject {
                        targets.forEach { tool ->
                            put(tool.name, buildJsonObject {
                                put("status", JsonPrimitive(if (tool.name in activeNameSet) "active" else "standby"))
                                put("description", JsonPrimitive(tool.description))
                                val schema = tool.parameters()
                                if (schema != null) {
                                    put("parameters", selectorJson.encodeToJsonElement(schema))
                                }
                            })
                        }
                    }
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("count", JsonPrimitive(targets.size))
                                put("schemas", schemas)
                            }.toString()
                        )
                    )
                }

                "invoke", "call", "run" -> {
                    val delegated = parseManageActiveToolsInvocation(input)
                        ?: return@Tool listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("error", JsonPrimitive("missing_tool_name"))
                                    put("detail", JsonPrimitive("Pass `tool_name` and optional `arguments` object when action is 'invoke'."))
                                }.toString()
                            )
                        )
                    if (!SubAgentToolPolicies.permits(conversationId, delegated.toolName)) {
                        return@Tool listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("error", JsonPrimitive("tool_not_allowed"))
                                    put("detail", JsonPrimitive("Tool '${delegated.toolName}' is not permitted by sub-agent tool policy."))
                                }.toString()
                            )
                        )
                    }
                    val targetTool = allTools.find { it.name == delegated.toolName }
                        ?: return@Tool listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("error", JsonPrimitive("tool_not_found"))
                                    put("detail", JsonPrimitive("Tool '${delegated.toolName}' is not among the ${allTools.size} available tools."))
                                }.toString()
                            )
                        )
                    val hardlineReason = HardlineCommandGuard.checkTool(delegated.toolName, delegated.arguments.toString())
                    if (hardlineReason != null) {
                        return@Tool listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put(
                                        "error",
                                        JsonPrimitive(
                                            "blocked by safety floor (hardline): $hardlineReason. " +
                                                "This command cannot run via the agent under any circumstances."
                                        )
                                    )
                                }.toString()
                            )
                        )
                    }
                    // Also promote into active window for subsequent steps in this conversation
                    ConversationToolSelectionState.enable(conversationId, listOf(delegated.toolName))
                    targetTool.execute(delegated.arguments)
                }

                else -> {
                    // "list"
                    val filtered = if (query.isBlank()) {
                        allTools
                    } else {
                        allTools.filter {
                            it.name.contains(query, ignoreCase = true) ||
                                it.description.contains(query, ignoreCase = true)
                        }
                    }
                    val standbyList = filtered.filter { it.name !in activeNameSet }
                    val activeList = filtered.filter { it.name in activeNameSet }
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("total_available", JsonPrimitive(allTools.size))
                                put("active_count", JsonPrimitive(partition.activeTools.size))
                                put("standby_count", JsonPrimitive(partition.standbyTools.size))
                                put("soft_cap", JsonPrimitive(partition.effectiveCap))
                                put("standby_tools", buildJsonArray {
                                    standbyList.forEach { tool ->
                                        add(buildJsonObject {
                                            put("name", JsonPrimitive(tool.name))
                                            put("description", JsonPrimitive(tool.description.take(160)))
                                        })
                                    }
                                })
                                put("active_tools", buildJsonArray {
                                    activeList.forEach { tool ->
                                        add(JsonPrimitive(tool.name))
                                    }
                                })
                            }.toString()
                        )
                    )
                }
            }
        },
    )
}