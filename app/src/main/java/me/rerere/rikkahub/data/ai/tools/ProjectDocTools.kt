package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.cartography.ProjectCartographer
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.io.File

/**
 * Creates the `update_project_doc` tool exposed to models and agents.
 *
 * Scans the project workspace and refreshes `.rikkahub/project.md` architectural facts
 * and dependencies on demand without expensive LLM loops.
 */
fun createUpdateProjectDocTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    cartographer: ProjectCartographer = ProjectCartographer(),
    needsApproval: ((toolName: String) -> Boolean)? = null,
): Tool = Tool(
    name = "update_project_doc",
    description = """
        Re-scan the workspace and refresh .rikkahub/project.md architectural facts, modules,
        and dependencies without expensive LLM loops. Call this after scaffolding or modifying project structure.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("hint", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional summary or architectural focus to record in the project document.")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval?.invoke("update_project_doc") ?: false },
    execute = { args ->
        val hint = args.jsonObject["hint"]?.jsonPrimitive?.contentOrNull

        val workspaceDir = workspaceRepository.resolveAbsoluteFile(workspaceId, "/workspace")
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Workspace root not found")
                        put("updated", false)
                    }.toString()
                )
            )

        val report = cartographer.survey(workspaceDir, hint)

        // Write the generated markdown to /workspace/.rikkahub/project.md
        val targetFile = File(workspaceDir, ProjectCartographer.PROJECT_DOC_RELATIVE_PATH)
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(report.generatedMarkdown)

        val resultJson = buildJsonObject {
            put("updated", true)
            put("path", ProjectCartographer.PROJECT_DOC_RELATIVE_PATH)
            put("projectType", report.projectType)
            put("modules", buildJsonArray { report.modules.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("techStack", buildJsonArray { report.techStack.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("summary", "Refreshed .rikkahub/project.md (${report.projectType}, ${report.modules.size} modules, ${report.techStack.size} technologies)")
        }

        listOf(UIMessagePart.Text(resultJson.toString()))
    }
)
