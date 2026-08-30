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

    /**
     * Single source of truth for time: [now] is what the tracker's injected clock
     * reads, and [advanceTo] moves that same clock forward (running any pending
     * timers). Keeping one clock for both avoids the classic fake-clock bug of
     * the injected nowMs drifting from the scheduler's internal time.
     */
    private class FakeClock {
        var now = 0L

        private val map = mutableMapOf<Runnable, Long>()
        fun schedule(r: Runnable, delay: Long) { map[r] = now + delay }
        fun cancel(r: Runnable) { map.remove(r) }

        fun advanceTo(t: Long) {
            now = t
            val due = map.filter { it.value <= t }.keys.toList()
            map.keys.retainAll(map.keys - due.toSet())
            due.forEach { r -> r.run() }
        }
        val pendingCount: Int get() = map.size
    }

    /** Builds a tracker backed by a controllable [FakeClock]. */
    private fun tracked(c: FakeClock) =
        HeartbeatTracker(nowMs = { c.now }, schedule = c::schedule, cancel = c::cancel)

    @Test
    fun heartbeatStartSchedulesFirstPing() {
        val c = FakeClock().apply { now = 1000L }
        val hb = tracked(c)

        assertFalse("heartbeat must not be running before start()", hb.started)
        hb.start()
        assertTrue(hb.started)
        // First ping scheduled (15 s).
        assertEquals(1, c.pendingCount)
    }

    @Test
    fun heartbeatSendsPingAndTimesOutWithoutPong() {
        val c = FakeClock().apply { now = 1000L }
        val hb = tracked(c)
        val pings = mutableListOf<Long>()
        hb.onPing = { ts -> pings.add(ts) }

        hb.start()
        // Nominal first ping at t = 1000 + 15000 = 16000.
        c.advanceTo(16_000)
        assertEquals("first ping scheduled for 16s", 1, pings.size)
        assertEquals(16_000L, pings[0])
        // No pong → advance past the 10 s pong timeout → count a miss.
        c.advanceTo(16_000 + HeartbeatTracker.PONG_TIMEOUT_MS)
        assertEquals("one missed pong without a pong", 1, hb.missedPongs)
    }

    @Test
    fun pongCancelsTimeoutAndMeasuresLatency() {
        val c = FakeClock().apply { now = 10_000L }
        val hb = tracked(c)
        hb.start() // schedules first ping at now + 15 s = 25_000

        c.advanceTo(25_000) // ping fires here: pingSentAt = 25_000

        c.advanceTo(25_030) // pong arrives 30 ms after the ping was sent
        hb.onPong()
        assertEquals("measured RTT should be 30 ms", 30L, hb.latencyMs ?: -1L)
        assertEquals("a successful pong resets misses", 0, hb.missedPongs)
    }

    @Test
    fun threeMissedPongsTriggersDead() {
        val c = FakeClock().apply { now = 1000L }
        val hb = tracked(c)
        var deadCalls = 0
        hb.onDead = { deadCalls++ }

        hb.start()
        assertEquals(0, deadCalls)

        // 3 ping cycles, never answering a pong.
        repeat(HeartbeatTracker.MAX_MISSED) { i ->
            val pingAt = 1000L + 15_000L * (i + 1)
            c.advanceTo(pingAt)            // ping fires
            c.advanceTo(pingAt + HeartbeatTracker.PONG_TIMEOUT_MS) // miss
        }
        assertEquals("onDead must fire after MAX_MISSED missed pongs", 1, deadCalls)
        assertFalse("heartbeat must stop after death", hb.started)
    }

    @Test
    fun stopCancelsPendingTimers() {
        val c = FakeClock().apply { now = 1000L }
        val hb = tracked(c)
        var pings = 0
        hb.onPing = { pings++ }

        hb.start()
        c.advanceTo(16_000)
        assertTrue(pings > 0)
        hb.stop()
        assertFalse("stop() must clear the started flag", hb.started)
        assertNull("stop() resets measured latency", hb.latencyMs)
        // Advancing time after stop must not produce further pings (timers cancelled).
        val before = pings
        c.advanceTo(100_000)
        assertEquals("no pings after stop", before, pings)
    }
}