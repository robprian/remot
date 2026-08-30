package com.robrion.remot.signaling

/**
 * Pure app-level WebSocket heartbeat state machine — deliberately free of any
 * Android/Hander dependency so it can be unit-tested as plain JVM code.
 *
 * Responsibilities (mirroring the old inline `ping`/`onPong`/`startHeartbeat`):
 * - start heartbeat scheduling (15 s ping interval) only after registration;
 * - measure a real ping→pong round-trip into [latencyMs];
 * - count consecutive missed pongs (10 s pong timeout) and fire [onDead] once
 *   they reach [MAX_MISSED], signalling the owner to drop the connection.
 *
 * Scheduling/timers are injected ([schedule]/[cancel]) and the clock is injected
 * ([nowMs]), so a test can drive time deterministically without a Looper.
 */
class HeartbeatTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
) {
    /** True from [start] until [stop] / death. Guards that no ping runs while off. */
    var started = false
        private set

    /** Consecutive pings with no pong in between. Reset on a successful pong. */
    var missedPongs = 0
        private set

    /** Most recently measured ping→pong round-trip in ms, or null if none yet. */
    var latencyMs: Long? = null
        private set

    /** Invoked when the owner should send a wire ping; receives the sent-at ts. */
    var onPing: ((ts: Long) -> Unit)? = null

    /** Invoked when [missedPongs] reached [MAX_MISSED] — treat the socket as dead. */
    var onDead: (() -> Unit)? = null

    private var pingRunnable = Runnable { onPingTimer() }
    private var timeoutRunnable = Runnable { onPongTimeout() }
    private var pingSentAt = 0L

    companion object {
        const val PING_INTERVAL_MS = 15_000L
        const val PONG_TIMEOUT_MS = 10_000L
        const val MAX_MISSED = 3
    }

    /** Begins scheduling pings. Idempotent; resets counters. */
    fun start() {
        if (started) return
        started = true
        missedPongs = 0
        latencyMs = null
        schedule(pingRunnable, PING_INTERVAL_MS)
    }

    /** Stops all heartbeat activity and resets state. Safe to call when not started. */
    fun stop() {
        started = false
        cancel(pingRunnable)
        cancel(timeoutRunnable)
        missedPongs = 0
        pingSentAt = 0L
    }

    /** A wire pong arrived → cancel the pending timeout, reset misses, measure latency. */
    fun onPong() {
        cancel(timeoutRunnable)
        missedPongs = 0
        if (pingSentAt > 0) latencyMs = nowMs() - pingSentAt
        pingSentAt = 0L
    }

    private fun onPingTimer() {
        if (!started) return
        pingSentAt = nowMs()
        onPing?.invoke(pingSentAt)
        schedule(timeoutRunnable, PONG_TIMEOUT_MS)
        schedule(pingRunnable, PING_INTERVAL_MS)
    }

    private fun onPongTimeout() {
        if (!started) return
        missedPongs++
        pingSentAt = 0L
        if (missedPongs >= MAX_MISSED) {
            started = false
            onDead?.invoke()
        }
    }
}