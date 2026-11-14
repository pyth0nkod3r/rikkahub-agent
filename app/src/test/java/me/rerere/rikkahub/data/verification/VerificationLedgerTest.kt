package me.rerere.rikkahub.data.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VerificationLedgerTest {

    private lateinit var ledger: VerificationLedger

    @Before
    fun setup() {
        ledger = VerificationLedger()
    }

    @Test
    fun `open verdict when no status claimed`() {
        val report = ledger.verifyTask("task_1")
        assertEquals(VerificationVerdict.OPEN, report.verdict)
        assertFalse(report.verified)
        assertTrue(report.ok)
    }

    @Test
    fun `unsuccessful verdict when task claims failure or skip`() {
        val report = ledger.verifyTask(
            taskId = "task_failed",
            claimedStatus = "FAILED",
            claimedError = "Network timeout"
        )
        assertEquals(VerificationVerdict.UNSUCCESSFUL, report.verdict)
        assertFalse(report.verified)
        assertTrue(report.ok) // ok because the system honestly settled as failed, no contradiction
    }

    @Test
    fun `confirmed verdict when status claims success and durable command agrees`() {
        val taskId = "compile_task"
        ledger.recordCommand(taskId, "kotlinc src -d out", exitCode = 0, stdout = "Success")
        ledger.recordFileWrite("/workspace/out/App.class", taskId, null, "sha256_mock_hash")

        val report = ledger.verifyTask(
            taskId = taskId,
            claimedStatus = "COMPLETED"
        )
        assertEquals(VerificationVerdict.CONFIRMED, report.verdict)
        assertTrue(report.verified)
        assertTrue(report.ok)
    }

    @Test
    fun `contradicted verdict when status claims success but command failed`() {
        val taskId = "test_task"
        ledger.recordCommand(taskId, "pytest tests/", exitCode = 1, stderr = "AssertionError")

        val report = ledger.verifyTask(
            taskId = taskId,
            claimedStatus = "COMPLETED"
        )
        assertEquals(VerificationVerdict.CONTRADICTED, report.verdict)
        assertFalse(report.verified)
        assertFalse(report.ok)
        assertTrue(report.findings.any { it.isProblem && it.check == VerificationCheck.COMMANDS })
    }

    @Test
    fun `contradicted verdict when status claims write success but file digest was no-op`() {
        val taskId = "edit_task"
        // preSha equals postSha -> no-op write claimed as edit
        ledger.recordFileWrite("/workspace/main.kt", taskId, "hash123", "hash123")

        val report = ledger.verifyTask(
            taskId = taskId,
            claimedStatus = "COMPLETED"
        )
        assertEquals(VerificationVerdict.CONTRADICTED, report.verdict)
        assertFalse(report.verified)
        assertFalse(report.ok)
        assertTrue(report.findings.any { it.isProblem && it.check == VerificationCheck.WRITES })
    }

    @Test
    fun `unconfirmed verdict when task claims completed but zero durable evidence was recorded`() {
        val taskId = "thinking_task"
        val report = ledger.verifyTask(
            taskId = taskId,
            claimedStatus = "COMPLETED"
        )
        assertEquals(VerificationVerdict.UNCONFIRMED, report.verdict)
        assertFalse(report.verified)
        assertTrue(report.ok) // Unconfirmed is a warning, not a contradiction
        assertTrue(report.findings.any { it.isWarning })
    }

    @Test
    fun `session verification reports contradiction if any unassociated command failed`() {
        ledger.recordCommand(null, "global_setup.sh", exitCode = 2, stderr = "permission denied")

        val sessionReport = ledger.verifySession("session_1")
        assertEquals(VerificationVerdict.CONTRADICTED, sessionReport.overallVerdict)
        assertFalse(sessionReport.ok)
        assertEquals(1, sessionReport.sessionFindings.size)
    }
}
