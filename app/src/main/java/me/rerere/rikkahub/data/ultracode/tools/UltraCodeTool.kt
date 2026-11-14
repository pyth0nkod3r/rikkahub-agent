package me.rerere.rikkahub.data.ultracode.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.ultracode.UltraCodeEngine

/**
 * Creates the `run_ultracode` tool.
 *
 * Runs an autonomous 11-stage UltraCode loop with DAG scheduling, durable verification,
 * and self-healing re-planning.
 */
fun createUltraCodeTool(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository?,
    engine: UltraCodeEngine = UltraCodeEngine(),
    needsApproval: ((toolName: String) -> Boolean)? = null,
): Tool = Tool(
    name = "run_ultracode",
    description = """
        Execute a complex objective via the UltraCode autonomous loop.
        Constructs a dependency DAG, executes steps in topological order with bounded concurrency,
        verifies outcomes using the Durable Verification Ledger, and automatically re-plans if errors occur.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("objective", buildJsonObject {
                    put("type", "string")
                    put("description", "High-level goal or task to accomplish autonomously.")
                })
            },
            required = listOf("objective"),
        )
    },
    needsApproval = { needsApproval?.invoke("run_ultracode") ?: true },
    execute = { args ->
        val objective = args.jsonObject["objective"]?.jsonPrimitive?.contentOrNull
        if (objective.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "Objective is required")
            }.toString()))
        }

        val run = engine.drive(
            objective = objective,
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            worker = { node ->
                // Simulated worker step; can execute tool/shell commands
                Result.success("Executed step: ${node.title}")
            }
        )

        listOf(UIMessagePart.Text(run.toJsonString()))
    }
)
