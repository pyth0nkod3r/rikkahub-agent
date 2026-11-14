package me.rerere.rikkahub.data.ai.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWatchdogControllerTest {

    @Test
    fun `controller tracks elapsed and idle seconds accurately`() {
        var currentTime = 10_000L
        val controller = StreamWatchdogController(
            idleTimeoutMs = 75_000L,
            stuckThresholdMs = 20_000L,
            canSwitchModel = true,
            candidateModelName = "claude-3-5-haiku",
            clock = { currentTime }
        )

        // Initial state at t = 10s
        val init = controller.tick(currentTime)
        assertEquals(0L, init.elapsedSeconds)
        assertEquals(0L, init.idleSeconds)
        assertFalse(init.isStuck)
        assertTrue(init.canSwitchModel)

        // Advance 15s to t = 25s with no chunks
        currentTime = 25_000L
        val state1 = controller.tick(currentTime)
        assertEquals(15L, state1.elapsedSeconds)
        assertEquals(15L, state1.idleSeconds)
        assertFalse(state1.isStuck)

        // Chunk arrives at t = 26s
        currentTime = 26_000L
        controller.onChunkReceived()
        val stateAfterChunk = controller.tick(currentTime)
        assertEquals(16L, stateAfterChunk.elapsedSeconds)
        assertEquals(0L, stateAfterChunk.idleSeconds)
        assertFalse(stateAfterChunk.isStuck)
    }

    @Test
    fun `controller reports stuck when idle duration exceeds threshold`() {
        var currentTime = 10_000L
        val controller = StreamWatchdogController(
            idleTimeoutMs = 75_000L,
            stuckThresholdMs = 20_000L,
            clock = { currentTime }
        )

        // Advance 21 seconds with no chunks
        currentTime = 31_000L
        val decision = controller.evaluate(currentTime)
        assertTrue(decision is WatchdogDecision.Stuck)

        val state = (decision as WatchdogDecision.Stuck).state
        assertTrue(state.isStuck)
        assertEquals(21L, state.idleSeconds)
        assertEquals(21L, state.elapsedSeconds)
    }

    @Test
    fun `extendDeadline postpones timeout expiration`() {
        var currentTime = 0L
        val controller = StreamWatchdogController(
            idleTimeoutMs = 75_000L,
            clock = { currentTime }
        )

        // At 76s without extension, timeout would expire
        currentTime = 76_000L
        val expiredDecision = controller.evaluate(currentTime)
        assertEquals(WatchdogDecision.TimeoutExpired, expiredDecision)

        // With 30s extension (deadline = 105s), 76s is not expired
        controller.extendDeadline(30L)
        val extendedDecision = controller.evaluate(currentTime)
        assertTrue(extendedDecision is WatchdogDecision.Stuck)

        // Past extended deadline (106s), timeout expires
        currentTime = 106_000L
        assertEquals(WatchdogDecision.TimeoutExpired, controller.evaluate(currentTime))
    }

    @Test
    fun `requestModelSwitch triggers immediate switch decision`() {
        var currentTime = 10_000L
        val controller = StreamWatchdogController(
            canSwitchModel = true,
            candidateModelName = "gpt-4o-mini",
            clock = { currentTime }
        )

        controller.requestModelSwitch()
        val decision = controller.evaluate(currentTime)
        assertEquals(WatchdogDecision.SwitchModelRequested, decision)
    }
}
