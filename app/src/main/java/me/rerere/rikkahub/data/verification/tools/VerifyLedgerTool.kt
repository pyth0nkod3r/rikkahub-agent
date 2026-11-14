package me.rerere.rikkahub.data.verification.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.verification.VerificationLedger

/**
 * Creates the `verify_ledger` tool.
 *
 * Verifies objective durable records (command exit codes, tool results, and pre/post file digests)
 * for a task or whole session against claimed outcomes.
 */
fun createVerifyLedgerTool(
    ledger: VerificationLedger = VerificationLedger.GLOBAL,
    needsApproval: ((toolName: String) -> Boolean)? = null,
): Tool = Tool(
    name = "verify_ledger",
    description = """
        Query the Durable Verification Ledger to independently verify task outcomes against
        command exit codes, tool errors, and pre/post SHA-256 file digests.
        Returns verdicts: open, unsuccessful, confirmed, contradicted, or unconfirmed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional taskId to check. If omitted, checks the session overall.")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval?.invoke("verify_ledger") ?: false },
    execute = { args ->
        val taskId = args.jsonObject["taskId"]?.jsonPrimitive?.contentOrNull

        if (taskId != null) {
            val report = ledger.verifyTask(taskId)
            listOf(UIMessagePart.Text(report.toJsonString()))
        } else {
            val sessionReport = ledger.verifySession("current")
            val json = buildJsonObject {
                put("overallVerdict", sessionReport.overallVerdict.code)
                put("isOk", sessionReport.ok)
                put("nodeReportsCount", sessionReport.nodeReports.size)
                put("sessionFindingsCount", sessionReport.sessionFindings.size)
            }
            listOf(UIMessagePart.Text(json.toString()))
        }
    }
)
