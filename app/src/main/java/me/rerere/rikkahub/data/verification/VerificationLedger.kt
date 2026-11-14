package me.rerere.rikkahub.data.verification

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe Durable Verification Ledger.
 *
 * Implements "Done is not verification":
 * Correlates task status claims with objective records (exit codes, tool outcomes, and pre/post SHA-256 file digests).
 */
class VerificationLedger {

    private val commandRecords = CopyOnWriteArrayList<CommandRecord>()
    private val toolRecords = CopyOnWriteArrayList<ToolCallRecord>()
    private val fileWriteRecords = CopyOnWriteArrayList<FileWriteRecord>()
    private val taskClaims = ConcurrentHashMap<String, Pair<String, String?>>() // taskId -> (status, error)

    fun recordCommand(
        taskId: String?,
        command: String,
        exitCode: Int,
        stdout: String? = null,
        stderr: String? = null,
        commandId: String = java.util.UUID.randomUUID().toString(),
    ): CommandRecord {
        val record = CommandRecord(
            commandId = commandId,
            taskId = taskId,
            command = command,
            exitCode = exitCode,
            stdoutSample = stdout?.take(500),
            stderrSample = stderr?.take(500),
        )
        commandRecords.add(record)
        return record
    }

    fun recordToolCall(
        toolCallId: String,
        taskId: String?,
        toolName: String,
        input: String,
        output: String? = null,
        preDigest: String? = null,
        postDigest: String? = null,
        success: Boolean = true,
        error: String? = null,
    ): ToolCallRecord {
        val record = ToolCallRecord(
            toolCallId = toolCallId,
            taskId = taskId,
            toolName = toolName,
            input = input,
            output = output?.take(500),
            preDigest = preDigest,
            postDigest = postDigest,
            success = success,
            error = error,
        )
        toolRecords.add(record)
        return record
    }

    fun recordFileWrite(
        path: String,
        taskId: String?,
        preSha256: String?,
        postSha256: String?,
    ): FileWriteRecord {
        val record = FileWriteRecord(
            path = path,
            taskId = taskId,
            preSha256 = preSha256,
            postSha256 = postSha256,
            changed = preSha256 != postSha256,
        )
        fileWriteRecords.add(record)
        return record
    }

    fun recordTaskClaim(taskId: String, status: String, error: String? = null) {
        taskClaims[taskId] = status to error
    }

    /**
     * Verifies the durable record against a task's claim.
     */
    fun verifyTask(
        taskId: String,
        claimedStatus: String? = taskClaims[taskId]?.first,
        claimedError: String? = taskClaims[taskId]?.second,
    ): VerificationReport {
        val findings = mutableListOf<Finding>()

        // 1. Status Check
        if (claimedStatus == null) {
            return VerificationReport(
                taskId = taskId,
                claimedStatus = null,
                verdict = VerificationVerdict.OPEN,
                findings = listOf(Finding(VerificationCheck.STATUS, isProblem = false, message = "No claim made for task $taskId")),
            )
        }

        val normStatus = claimedStatus.uppercase()
        if (normStatus in setOf("FAILED", "CANCELLED", "SKIPPED")) {
            findings.add(
                Finding(
                    check = VerificationCheck.STATUS,
                    isProblem = false,
                    message = "Task settled as $normStatus (error: ${claimedError ?: "none"})"
                )
            )
            return VerificationReport(
                taskId = taskId,
                claimedStatus = claimedStatus,
                verdict = VerificationVerdict.UNSUCCESSFUL,
                findings = findings,
            )
        }

        if (normStatus !in setOf("COMPLETED", "SUCCESS", "DONE")) {
            return VerificationReport(
                taskId = taskId,
                claimedStatus = claimedStatus,
                verdict = VerificationVerdict.OPEN,
                findings = listOf(Finding(VerificationCheck.STATUS, isProblem = false, message = "Task is currently in $claimedStatus state")),
            )
        }

        // The task claims COMPLETED / SUCCESS. We must verify if the durable record agrees!
        var hasPositiveEvidence = false

        // 2. Commands Check
        val taskCommands = commandRecords.filter { it.taskId == taskId }
        for (cmd in taskCommands) {
            if (cmd.exitCode != 0) {
                findings.add(
                    Finding(
                        check = VerificationCheck.COMMANDS,
                        isProblem = true,
                        message = "Command failed with exit code ${cmd.exitCode}: ${cmd.command.take(60)} (stderr: ${cmd.stderrSample ?: "none"})"
                    )
                )
            } else {
                hasPositiveEvidence = true
                findings.add(
                    Finding(
                        check = VerificationCheck.COMMANDS,
                        isProblem = false,
                        message = "Command exited cleanly with code 0: ${cmd.command.take(60)}"
                    )
                )
            }
        }

        // 3. Tools Check
        val taskTools = toolRecords.filter { it.taskId == taskId }
        for (tool in taskTools) {
            if (!tool.success || tool.error != null) {
                findings.add(
                    Finding(
                        check = VerificationCheck.TOOLS,
                        isProblem = true,
                        message = "Tool ${tool.toolName} failed: ${tool.error ?: "unknown error"}"
                    )
                )
            } else {
                hasPositiveEvidence = true
                findings.add(
                    Finding(
                        check = VerificationCheck.TOOLS,
                        isProblem = false,
                        message = "Tool ${tool.toolName} succeeded"
                    )
                )
            }
        }

        // 4. Writes Check (Pre/Post Digests)
        val taskWrites = fileWriteRecords.filter { it.taskId == taskId }
        for (write in taskWrites) {
            if (!write.changed && write.preSha256 != null) {
                findings.add(
                    Finding(
                        check = VerificationCheck.WRITES,
                        isProblem = true,
                        message = "File write to ${write.path} was a no-op; pre-digest matches post-digest (${write.preSha256.take(8)})"
                    )
                )
            } else {
                hasPositiveEvidence = true
                findings.add(
                    Finding(
                        check = VerificationCheck.WRITES,
                        isProblem = false,
                        message = "File write verified for ${write.path} (${write.preSha256?.take(8) ?: "new"} -> ${write.postSha256?.take(8)})"
                    )
                )
            }
        }

        // Derive Verdict strictly from findings
        val hasProblems = findings.any { it.isProblem }
        val verdict = when {
            hasProblems -> VerificationVerdict.CONTRADICTED
            hasPositiveEvidence -> VerificationVerdict.CONFIRMED
            else -> {
                findings.add(
                    Finding(
                        check = VerificationCheck.STATUS,
                        isProblem = false,
                        isWarning = true,
                        message = "No commands, tools, or writes were recorded for this task"
                    )
                )
                VerificationVerdict.UNCONFIRMED
            }
        }

        return VerificationReport(
            taskId = taskId,
            claimedStatus = claimedStatus,
            verdict = verdict,
            findings = findings,
        )
    }

    /**
     * Verifies the whole session / execution graph.
     */
    fun verifySession(
        sessionId: String,
        taskIds: Set<String> = (commandRecords.mapNotNull { it.taskId } + toolRecords.mapNotNull { it.taskId }).toSet(),
    ): SessionVerificationReport {
        val nodeReports = taskIds.associateWith { verifyTask(it) }
        val sessionFindings = mutableListOf<Finding>()

        // Check session-level unassociated commands
        val unassociatedCommands = commandRecords.filter { it.taskId == null }
        for (cmd in unassociatedCommands) {
            if (cmd.exitCode != 0) {
                sessionFindings.add(
                    Finding(
                        check = VerificationCheck.COMMANDS,
                        isProblem = true,
                        message = "Session command failed with exit code ${cmd.exitCode}: ${cmd.command.take(60)}"
                    )
                )
            }
        }

        // Check session-level unassociated tools
        val unassociatedTools = toolRecords.filter { it.taskId == null }
        for (tool in unassociatedTools) {
            if (!tool.success) {
                sessionFindings.add(
                    Finding(
                        check = VerificationCheck.TOOLS,
                        isProblem = true,
                        message = "Session tool ${tool.toolName} failed: ${tool.error ?: "unknown"}"
                    )
                )
            }
        }

        val hasSessionProblem = sessionFindings.any { it.isProblem }
        val hasNodeContradiction = nodeReports.values.any { it.verdict == VerificationVerdict.CONTRADICTED }

        val overallVerdict = when {
            hasSessionProblem || hasNodeContradiction -> VerificationVerdict.CONTRADICTED
            nodeReports.values.all { it.verdict == VerificationVerdict.CONFIRMED } && nodeReports.isNotEmpty() -> VerificationVerdict.CONFIRMED
            nodeReports.values.any { it.verdict == VerificationVerdict.UNSUCCESSFUL } -> VerificationVerdict.UNSUCCESSFUL
            else -> VerificationVerdict.UNCONFIRMED
        }

        return SessionVerificationReport(
            sessionId = sessionId,
            overallVerdict = overallVerdict,
            nodeReports = nodeReports,
            sessionFindings = sessionFindings,
        )
    }

    fun clear() {
        commandRecords.clear()
        toolRecords.clear()
        fileWriteRecords.clear()
        taskClaims.clear()
    }

    companion object {
        val GLOBAL = VerificationLedger()

        fun computeDigest(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun computeFileDigest(file: File): String? {
            if (!file.exists() || !file.isFile) return null
            return runCatching {
                computeDigest(file.readBytes())
            }.getOrNull()
        }
    }
}
