package me.rerere.rikkahub.data.fileintel.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.fileintel.FileIntelligence
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.io.File

/**
 * Creates the `inspect_file` tool for LLMs and autonomous agents.
 */
fun createInspectFileTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: ((toolName: String) -> Boolean)? = null,
): Tool = Tool(
    name = "inspect_file",
    description = """
        Inspect a file with content-based detection and deep structure analysis (.apk, .aar, .zip, .pdf, media, code).
        Sniffs magic bytes to detect true format, checks extension spoofing, analyzes archive contents and manifests,
        and flags security risks (Zip Slip, Zip Bomb, extension mismatch).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative or absolute path of the file to inspect.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval?.invoke("inspect_file") ?: false },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "Missing required 'path' parameter")
            }.toString()))
        }

        val file = workspaceRepository.resolveAbsoluteFile(workspaceId, path)
            ?: File(path)

        if (!file.exists()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "File does not exist: $path")
            }.toString()))
        }

        val report = FileIntelligence.inspect(file)
        listOf(UIMessagePart.Text(report.toJsonString()))
    }
)
