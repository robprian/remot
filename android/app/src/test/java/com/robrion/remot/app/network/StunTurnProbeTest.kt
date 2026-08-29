package com.robrion.remot.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StunTurnProbe]. They run entirely against a mock STUN/TURN
 * server on the loopback interface (no emulator, no external network), so the
 * results are deterministic. The key assertions mirror the product rule "never
 * fake TURN": STUN reachability alone must not report TURN healthy, and an
 * unreachable server must report failure, never a fabricated success.
 */
class StunTurnProbeTest {

    @Test
    fun stunReachableWithoutCredentialsIsReportedHonestly() {
        val server = MockStunTurnServer().start()
        try {
            val result = StunTurnProbe(
                server.host, server.port, username = null, password = null
            ).probe()

            assertTrue("DNS should resolve", result.dnsOk)
            assertTrue("STUN binding should succeed", result.stunOk)
            assertFalse("TURN must NOT be faked when credentials are missing", result.turnOk)
            assertEquals("no-credentials", result.error)
            assertNotNull("STUN RTT should be measured when STUN alone works", result.latencyMs)
            assertTrue(server.bindingRequests > 0)
        } finally {
            server.stop()
        }
    }

    @Test
    fun authenticatedAllocateSucceeds() {
        val server = MockStunTurnServer().start()
        try {
            val result = StunTurnProbe(
                server.host, server.port, username = "user", password = "pass"
            ).probe()

            assertTrue("DNS should resolve", result.dnsOk)
            assertTrue("STUN binding should succeed", result.stunOk)
            assertTrue("TURN Allocate should succeed with credentials", result.turnOk)
            assertEquals(null, result.error)
            assertNotNull("TURN RTT should be measured on a successful Allocate", result.latencyMs)
            assertTrue("probe should perform challenge + authenticated Allocate", server.allocateRequests >= 2)
        } finally {
            server.stop()
        }
    }

    @Test
    fun unreachableServerTimesOutAndIsNotFaked() {
        // A bound-but-silent socket swallows the probe's packets and never replies.
        val silent = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            val result = StunTurnProbe(
                "127.0.0.1", silent.localPort, username = "u", password = "p"
            ).probe(timeoutMs = 500)

            assertTrue(result.dnsOk)
            assertFalse(result.stunOk)
            assertFalse(result.turnOk)
            assertEquals("timeout", result.error)
            assertEquals(null, result.latencyMs)
        } finally {
            silent.close()
        }
    }

    @Test
    fun unresolvableHostReportsDnsFailure() {
        // ".invalid" is reserved (RFC 2606) — resolution always fails, quickly.
        val result = StunTurnProbe("nonexistent-remot.invalid", 3478, "user", "pass").probe()

        assertFalse(result.dnsOk)
        assertFalse(result.stunOk)
        assertFalse(result.turnOk)
        assertEquals("dns", result.error)
    }
}

/**
 * Minimal loopback STUN/TURN responder. Speaks just enough RFC 5389 / RFC 5766
 * to exercise [StunTurnProbe]: answers STUN Binding, challenges TURN Allocate
 * with REALM + NONCE, and accepts an authenticated Allocate (detected by the
 * presence of a USERNAME attribute) with a success response. Runs on a daemon
 * thread; only ever bound to 127.0.0.1.
 */
private class MockStunTurnServer {

    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

    /** Hostname for the probe; always loopback. */
    val host: String get() = "127.0.0.1"

    /** Assigned ephemeral loopback port the probe should target. */
    val port: Int get() = socket.localPort

    var bindingRequests = 0
    var allocateRequests = 0

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(): MockStunTurnServer {
        running = true
        thread = Thread {
            while (running) {
                val buf = ByteArray(1500)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                } catch (e: Exception) {
                    if (running) continue else break
                }
                handle(pkt.data.copyOf(pkt.length), pkt.address, pkt.port)
            }
        }.apply { isDaemon = true }
        thread?.start()
        return this
    }

    fun stop() {
        running = false
        socket.close()
        thread?.join(1000)
    }

    private fun handle(msg: ByteArray, replyAddr: InetAddress, replyPort: Int) {
        val type = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
        val txId = msg.copyOfRange(8, 20)
        when (type) {
            // STUN Binding request → success response
            0x0001 -> {
                bindingRequests++
                sendReply(replyAddr, replyPort, 0x0101, txId, emptyList())
            }
            // TURN Allocate → 401 challenge (no USERNAME) or success (authenticated)
            0x0003 -> {
                allocateRequests++
                if (attrsOf(msg).any { it.first == USERNAME_ATTR }) {
                    sendReply(replyAddr, replyPort, 0x0103, txId, emptyList())
                } else {
                    sendReply(replyAddr, replyPort, 0x0113, txId, listOf(
                        REALM_ATTR to "remot.test.realm".toByteArray(),
                        NONCE_ATTR to "nonce-1234567890".toByteArray(),
                    ))
                }
            }
        }
    }

    private fun attrsOf(msg: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 20 // after the 20-byte STUN header
        while (i + 4 <= msg.size) {
            val type = ((msg[i].toInt() and 0xFF) shl 8) or (msg[i + 1].toInt() and 0xFF)
            val len = ((msg[i + 2].toInt() and 0xFF) shl 8) or (msg[i + 3].toInt() and 0xFF)
            if (i + 4 + len > msg.size) break
            out.add(type to msg.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len + ((4 - (len % 4)) % 4)
        }
        return out
    }

    private fun sendReply(
        addr: InetAddress,
        port: Int,
        type: Int,
        txId: ByteArray,
        attrs: List<Pair<Int, ByteArray>>,
    ) {
        val attrsLen = attrs.sumOf { (_, v) -> 4 + v.size + ((4 - (v.size % 4)) % 4) }
        val buf = ByteBuffer.allocate(20 + attrsLen)
        buf.putShort(type.toShort())
        buf.putShort(attrsLen.toShort())
        buf.putInt(MAGIC_COOKIE)
        buf.put(txId)
        for ((at, value) in attrs) {
            buf.putShort(at.toShort())
            buf.putShort(value.size.toShort())
            buf.put(value)
            repeat((4 - (value.size % 4)) % 4) { buf.put(0) }
        }
        socket.send(DatagramPacket(buf.array(), buf.position(), addr, port))
    }

    private companion object {
        const val MAGIC_COOKIE = 0x2112A442
        const val USERNAME_ATTR = 0x0006 // RFC 5389
        const val REALM_ATTR = 0x0014    // RFC 5389
        const val NONCE_ATTR = 0x0015    // RFC 5389
    }
}