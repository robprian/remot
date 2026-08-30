package com.robrion.remot.signaling

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure, JVM-friendly pieces of [SignalingClient]:
 *
 * 1. **Nonce echo** — the registration `auth-response` MUST echo the challenge
 *    `nonce` verbatim and sign it. This pins the fix for the V2C004P05 bug where
 *    the client omitted the nonce and the server rejected every registration
 *    with `auth-failed` (`SignalingMessages.authResponse`).
 * 2. **Heartbeat state machine** — [HeartbeatTracker] must start pinging only
 *    after `start()`, measure a real ping→pong RTT into `latencyMs`, and fire
 *    `onDead` only after [HeartbeatTracker.MAX_MISSED] consecutive missed pongs
 *    (the "unauthenticated socket keeps pinging / stale heartbeat timers" bug).
 *
 * Both run as plain JUnit on the JVM with a fake clock + injected scheduler —
 * no Android classes, no Robolectric.
 */
class SignalingClientTest {

    // ---------- nonce echo (SignalingMessages) ----------

    @Test
    fun authResponseEchoesNonceVerbatim() {
        val nonce = "MQ/dk2pKfzK+cG/VvnrQjkr7kU8rSPpDqgmQxJhcW60="
        val m = SignalingMessages.authResponse(to = "", nonceB64 = nonce, sigB64 = "c2ln")
        assertEquals("auth-response", m.getString("type"))
        // THE critical assertion: the nonce is echoed unchanged.
        assertEquals(nonce, m.getString("nonce"))
        assertEquals("c2ln", m.getString("sig"))
    }

    @Test
    fun authResponseIncludesToOnlyForPeerRelay() {
        // Server-direct registration: `to` is blank and must NOT appear in the JSON.
        val direct = SignalingMessages.authResponse(to = "", nonceB64 = "bg", sigB64 = "sg")
        assertFalse("server-direct auth-response must not carry a `to` field", direct.has("to"))

        // Peer-relayed per-session auth: `to` must be present.
        val relayed = SignalingMessages.authResponse(to = "peer-device-id", nonceB64 = "bg", sigB64 = "sg")
        assertTrue(relayed.has("to"))
        assertEquals("peer-device-id", relayed.getString("to"))
    }

    @Test
    fun authResponseDoesNotDropDistinctNonceBytes() {
        // A nonce containing base64 padding and slashes must survive round-trip.
        val nonce = "AA==" // minimal, padding-heavy value
        val m = SignalingMessages.authResponse(to = "", nonceB64 = nonce, sigB64 = "x")
        assertEquals(nonce, m.getString("nonce"))
    }

    // ---------- heartbeat state machine (HeartbeatTracker) ----------

    /** Fake scheduler that records the longest-delay runnable still due. */
    private class FakeScheduler {
        private val map = mutableMapOf<Runnable, Long>()
        private var clock = 0L

        fun schedule(r: Runnable, delay: Long) { map[r] = clock + delay }
        fun cancel(r: Runnable) { map.remove(r) }

        /** Advance the clock and run every runnable whose delay has elapsed. */
        fun advanceTo(t: Long) {
            clock = t
            val due = map.filter { it.value <= t }.keys.toList()
            map.keys.retainAll(map.keys - due.toSet())
            due.forEach { r -> r.run() }
        }
        val pendingCount: Int get() = map.size
    }

    /** Builds a tracker backed by a controllable clock + [FakeScheduler]. */
    private fun tracked(
        now: () -> Long,
        sched: FakeScheduler,
    ) = HeartbeatTracker(nowMs = now, schedule = sched::schedule, cancel = sched::cancel)

    @Test
    fun heartbeatStartSchedulesFirstPing() {
        val sched = FakeScheduler()
        var clock = 1000L
        val hb = tracked(now = { clock }, sched)

        assertFalse("heartbeat must not be running before start()", hb.started)
        hb.start()
        assertTrue(hb.started)
        // First ping scheduled (15 s).
        assertEquals(1, sched.pendingCount)
    }

    @Test
    fun heartbeatSendsPingAndTimesOutWithoutPong() {
        val sched = FakeScheduler()
        var clock = 1000L
        val hb = tracked(now = { clock }, sched)
        val pings = mutableListOf<Long>()
        hb.onPing = { ts -> pings.add(ts) }

        hb.start()
        // Nominal first ping at t=16000.
        sched.advanceTo(16_000)
        assertEquals(1, pings.size)
        assertEquals(16_000L, pings[0])
        // No pong → advance past the 10 s pong timeout → count a miss.
        sched.advanceTo(16_000 + HeartbeatTracker.PONG_TIMEOUT_MS)
        assertEquals("one missed pong without a pong", 1, hb.missedPongs)
    }

    @Test
    fun pongCancelsTimeoutAndMeasuresLatency() {
        val sched = FakeScheduler()
        var clock = 10_000L
        val hb = tracked(now = { clock }, sched)
        hb.start() // schedules first ping at clock + 15 s = 25_000

        clock = 25_000L // ping fires here: pingSentAt = 25_000
        sched.advanceTo(25_000)

        clock = 25_030L // pong arrives 30 ms after the ping was sent
        hb.onPong()
        assertEquals("measured RTT should be 30 ms", 30L, hb.latencyMs ?: -1L)
        assertEquals("a successful pong resets misses", 0, hb.missedPongs)
    }

    @Test
    fun threeMissedPongsTriggersDead() {
        val sched = FakeScheduler()
        var clock = 1000L
        val hb = tracked(now = { clock }, sched)
        var deadCalls = 0
        hb.onDead = { deadCalls++ }

        hb.start()
        assertEquals(0, deadCalls)

        // 3 ping cycles, never answering a pong.
        repeat(HeartbeatTracker.MAX_MISSED) { i ->
            val pingAt = 1000L + 15_000L * (i + 1)
            clock = pingAt
            sched.advanceTo(pingAt)            // ping fires
            sched.advanceTo(pingAt + HeartbeatTracker.PONG_TIMEOUT_MS) // miss
        }
        assertEquals("onDead must fire after MAX_MISSED missed pongs", 1, deadCalls)
        assertFalse("heartbeat must stop after death", hb.started)
    }

    @Test
    fun stopCancelsPendingTimers() {
        val sched = FakeScheduler()
        var clock = 1000L
        val hb = tracked(now = { clock }, sched)
        var pings = 0
        hb.onPing = { pings++ }

        hb.start()
        sched.advanceTo(16_000)
        assertTrue(pings > 0)
        hb.stop()
        assertFalse("stop() must clear the started flag", hb.started)
        assertNull("stop() resets measured latency", hb.latencyMs)
        // Advancing time after stop must not produce further pings (timers cancelled).
        val before = pings
        sched.advanceTo(100_000)
        assertEquals("no pings after stop", before, pings)
    }
}