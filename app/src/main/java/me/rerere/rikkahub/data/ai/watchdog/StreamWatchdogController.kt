package me.rerere.rikkahub.data.ai.watchdog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed class WatchdogDecision {
    data object Active : WatchdogDecision()
    data class Stuck(val state: StreamHeartbeatState) : WatchdogDecision()
    data object SwitchModelRequested : WatchdogDecision()
    data object TimeoutExpired : WatchdogDecision()
}

/**
 * Controller and active timer managing streaming heartbeats, stuck detection,
 * and interactive user stuck resolutions.
 */
class StreamWatchdogController(
    val idleTimeoutMs: Long = 75_000L,
    val stuckThresholdMs: Long = 20_000L,
    val canSwitchModel: Boolean = false,
    val candidateModelName: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startTimeMs = clock()
    private val lastChunkTimeMs = AtomicLong(startTimeMs)
    private val extensionMs = AtomicLong(0L)
    private val switchRequested = AtomicBoolean(false)

    private val _heartbeatState = MutableStateFlow<StreamHeartbeatState?>(null)
    val heartbeatState: StateFlow<StreamHeartbeatState?> = _heartbeatState.asStateFlow()

    private var tickerJob: Job? = null

    /**
     * Start the 1-second heartbeat ticker coroutine.
     */
    fun start(scope: CoroutineScope) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(1000L)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        _heartbeatState.value = null
    }

    fun onChunkReceived() {
        lastChunkTimeMs.set(clock())
        tick()
    }

    fun extendDeadline(seconds: Long = 30L) {
        extensionMs.addAndGet(seconds * 1000L)
        tick()
    }

    fun requestModelSwitch() {
        switchRequested.set(true)
        tick()
    }

    fun evaluate(nowMs: Long = clock()): WatchdogDecision {
        if (switchRequested.get()) {
            return WatchdogDecision.SwitchModelRequested
        }

        val lastChunk = lastChunkTimeMs.get()
        val idleMs = nowMs - lastChunk
        val effectiveTimeoutMs = idleTimeoutMs + extensionMs.get()

        if (idleTimeoutMs > 0 && idleMs >= effectiveTimeoutMs) {
            return WatchdogDecision.TimeoutExpired
        }

        val isStuck = idleMs >= stuckThresholdMs
        val elapsedSec = (nowMs - startTimeMs).coerceAtLeast(0L) / 1000L
        val idleSec = idleMs.coerceAtLeast(0L) / 1000L

        val state = StreamHeartbeatState(
            elapsedSeconds = elapsedSec,
            idleSeconds = idleSec,
            isStuck = isStuck,
            canSwitchModel = canSwitchModel,
            candidateModelName = candidateModelName,
            statusText = if (isStuck) {
                "Streaming stalled (${elapsedSec}s elapsed, idle ${idleSec}s)"
            } else {
                "Streaming (${elapsedSec}s elapsed)"
            }
        )

        return if (isStuck) WatchdogDecision.Stuck(state) else WatchdogDecision.Active
    }

    fun tick(nowMs: Long = clock()): StreamHeartbeatState {
        val lastChunk = lastChunkTimeMs.get()
        val idleMs = nowMs - lastChunk
        val isStuck = idleMs >= stuckThresholdMs
        val elapsedSec = (nowMs - startTimeMs).coerceAtLeast(0L) / 1000L
        val idleSec = idleMs.coerceAtLeast(0L) / 1000L

        val state = StreamHeartbeatState(
            elapsedSeconds = elapsedSec,
            idleSeconds = idleSec,
            isStuck = isStuck,
            canSwitchModel = canSwitchModel,
            candidateModelName = candidateModelName,
            statusText = if (isStuck) {
                "Streaming stalled (${elapsedSec}s elapsed, idle ${idleSec}s)"
            } else {
                "Streaming (${elapsedSec}s elapsed)"
            }
        )

        _heartbeatState.value = state
        return state
    }
}
