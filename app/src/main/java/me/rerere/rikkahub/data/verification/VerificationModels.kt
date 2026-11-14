package me.rerere.rikkahub.data.verification

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CommandRecord(
    val commandId: String,
    val taskId: String?,
    val command: String,
    val exitCode: Int,
    val stdoutSample: String? = null,
    val stderrSample: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class ToolCallRecord(
    val toolCallId: String,
    val taskId: String?,
    val toolName: String,
    val input: String,
    val output: String? = null,
    val preDigest: String? = null,
    val postDigest: String? = null,
    val success: Boolean,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class FileWriteRecord(
    val path: String,
    val taskId: String?,
    val preSha256: String?,
    val postSha256: String?,
    val changed: Boolean = preSha256 != postSha256,
    val timestamp: Long = System.currentTimeMillis(),
)

data class Finding(
    val check: VerificationCheck,
    val isProblem: Boolean,
    val isWarning: Boolean = false,
    val message: String,
)

data class VerificationReport(
    val taskId: String?,
    val claimedStatus: String?,
    val verdict: VerificationVerdict,
    val findings: List<Finding>,
    val verified: Boolean = verdict == VerificationVerdict.CONFIRMED,
    val ok: Boolean = verdict != VerificationVerdict.CONTRADICTED,
) {
    fun toJsonString(): String = buildJsonObject {
        put("taskId", taskId)
        put("claimedStatus", claimedStatus)
        put("verdict", verdict.code)
        put("verified", verified)
        put("ok", ok)
        put("findings", buildJsonArray {
            findings.forEach { f ->
                add(buildJsonObject {
                    put("check", f.check.identifier)
                    put("isProblem", f.isProblem)
                    put("isWarning", f.isWarning)
                    put("message", f.message)
                })
            }
        })
    }.toString()
}

data class SessionVerificationReport(
    val sessionId: String,
    val overallVerdict: VerificationVerdict,
    val nodeReports: Map<String, VerificationReport>,
    val sessionFindings: List<Finding>,
    val ok: Boolean = overallVerdict != VerificationVerdict.CONTRADICTED,
)
