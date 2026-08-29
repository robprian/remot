package com.robrion.remot.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Result of probing a STUN/TURN server, separated by layer so the UI can show
 * DNS / STUN / TURN independently instead of one fake "connected".
 */
data class StunTurnResult(
    val dnsOk: Boolean,
    val stunOk: Boolean,
    val turnOk: Boolean,
    /** Measured RTT of the last successful TURN Allocate (or STUN if only STUN worked), ms. */
    val latencyMs: Long? = null,
    val error: String? = null,
) {
    companion object {
        val unknown = StunTurnResult(dnsOk = false, stunOk = false, turnOk = false, error = "not checked")
    }
}

/**
 * Minimal STUN (RFC 5389) / TURN (RFC 5766) client used ONLY for connectivity
 * health checks. It performs:
 *
 *   1. DNS resolution of the TURN hostname.
 *   2. A real STUN Binding request/response over UDP (proves the server speaks
 *      STUN and is reachable) — measured RTT.
 *   3. A real TURN Allocate handshake (401 realm/nonce challenge then
 *      authenticated Allocate with the short-lived credentials the signaling
 *      server issued) — proves the TURN relay actually accepts allocations.
 *
 * Everything runs on the caller's (background) dispatcher. Never on the main
 * thread. No secrets are logged.
 */
class StunTurnProbe(
    private val host: String,
    private val port: Int = 3478,
    private val username: String? = null,
    private val password: String? = null,
) {
    private val logTag = "StunTurnProbe"

    /** Runs the full probe. Must be called off the main thread. */
    fun probe(timeoutMs: Long = 4000): StunTurnResult {
        val address: InetAddress = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            Log.w(logTag, "DNS failed for $host: ${e.message}")
            return StunTurnResult(dnsOk = false, stunOk = false, turnOk = false, error = "dns")
        }

        val socket = DatagramSocket()
        try {
            socket.soTimeout = timeoutMs.toInt()
            socket.connect(InetSocketAddress(address, port))

            // --- STUN binding ---
            val bindingStart = System.nanoTime()
            val bindingTx = randomTransactionId()
            val bindingReq = buildBindingRequest(bindingTx)
            socket.send(DatagramPacket(bindingReq, bindingReq.size))
            val resp = receive(socket)
            val stunOk = parseBindingResponse(resp, bindingTx)
            if (!stunOk) {
                return StunTurnResult(
                    dnsOk = true, stunOk = false, turnOk = false,
                    error = "stun", latencyMs = null
                )
            }
            val stunRttMs = (System.nanoTime() - bindingStart) / 1_000_000

            // --- TURN allocate (requires credentials) ---
            if (username == null || password == null) {
                // No credentials available (server not reachable / not registered):
                // STUN worked, TURN can't be authenticated → report honestly.
                return StunTurnResult(
                    dnsOk = true, stunOk = true, turnOk = false,
                    latencyMs = stunRttMs, error = "no-credentials"
                )
            }
            val turnStart = System.nanoTime()
            val turnTx = randomTransactionId()
            val turnOk = allocate(socket, turnTx)
            val turnRttMs = if (turnOk) (System.nanoTime() - turnStart) / 1_000_000 else null
            return StunTurnResult(
                dnsOk = true, stunOk = true, turnOk = turnOk,
                latencyMs = turnRttMs ?: stunRttMs,
                error = if (turnOk) null else "turn"
            )
        } catch (e: SocketTimeoutException) {
            return StunTurnResult(dnsOk = true, stunOk = false, turnOk = false, error = "timeout")
        } catch (e: Exception) {
            Log.w(logTag, "probe failed: ${e.message}")
            return StunTurnResult(dnsOk = true, stunOk = false, turnOk = false, error = e.message)
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---- STUN wire helpers ----

    private fun randomTransactionId(): ByteArray =
        ByteArray(12).also { Random.nextBytes(it) }

    private fun buildBindingRequest(txId: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(20)
        header.putShort(0x0001)              // Binding request
        header.putShort(0)                   // length (no attributes)
        header.putInt(MAGIC_COOKIE)
        header.put(txId)
        return header.array()
    }

    private fun receive(socket: DatagramSocket): ByteArray {
        val buf = ByteArray(1500)
        val pkt = DatagramPacket(buf, buf.size)
        socket.receive(pkt)
        return pkt.data.copyOf(pkt.length)
    }

    /** Parses a STUN Binding response; returns true when the transaction matches and it's a success response. */
    private fun parseBindingResponse(data: ByteArray, txId: ByteArray): Boolean {
        if (data.size < 20) return false
        val bb = ByteBuffer.wrap(data)
        val type = bb.short.toInt() and 0xFFFF
        bb.short // skip the 2-byte STUN header length field
        val cookie = bb.int
        val respTx = ByteArray(12).also { bb.get(it) }
        if (cookie != MAGIC_COOKIE) return false
        if (!respTx.contentEquals(txId)) return false
        return type == 0x0101 // Binding success response
    }

    // ---- TURN Allocate ----

    /**
     * RFC 5766 Allocate handshake:
     *   request (no auth) → 401 with REALM + NONCE → authenticated request with
     *   USERNAME + MESSAGE-INTEGRITY → 0x0103 success (XOR-RELAYED-ADDRESS).
     */
    private fun allocate(socket: DatagramSocket, txId: ByteArray): Boolean {
        // Attempt 1: unauthenticated Allocate (type 0x0003)
        val req1 = buildAllocateRequest(txId, username = null, realm = null, nonce = null, integrityKey = null)
        socket.send(DatagramPacket(req1, req1.size))
        val resp1 = receive(socket)
        val challenge = parseAllocateChallenge(resp1, txId) ?: return false
        if (challenge.realm == null || challenge.nonce == null) return false

        // Attempt 2: authenticated Allocate
        val key = md5("$username:${challenge.realm}:$password")
        val req2 = buildAllocateRequest(
            txId, username = username, realm = challenge.realm, nonce = challenge.nonce, integrityKey = key
        )
        socket.send(DatagramPacket(req2, req2.size))
        val resp2 = receive(socket)
        return parseAllocateSuccess(resp2, txId)
    }

    private data class AllocateChallenge(val realm: String?, val nonce: String?)

    private fun parseAllocateChallenge(data: ByteArray, txId: ByteArray): AllocateChallenge? {
        if (data.size < 20) return null
        val bb = ByteBuffer.wrap(data)
        val type = bb.short.toInt() and 0xFFFF
        bb.short // skip the 2-byte STUN header length field
        val cookie = bb.int
        val respTx = ByteArray(12).also { bb.get(it) }
        if (cookie != MAGIC_COOKIE || !respTx.contentEquals(txId)) return null
        if (type != 0x0113) return null // 401 Unauthorized → challenge
        // Walk attributes: REALM (0x0014), NONCE (0x0015)
        var realm: String? = null
        var nonce: String? = null
        while (bb.remaining() >= 4) {
            val at = bb.short.toInt() and 0xFFFF
            val len = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < len) break
            val value = ByteArray(len).also { bb.get(it) }
            when (at) {
                0x0014 -> realm = String(value, Charsets.UTF_8).trimEnd('\u0000')
                0x0015 -> nonce = String(value, Charsets.UTF_8).trimEnd('\u0000')
            }
            val pad = (len % 4).let { if (it == 0) 0 else 4 - it }
            if (bb.remaining() >= pad) bb.position(bb.position() + pad)
        }
        return AllocateChallenge(realm, nonce)
    }

    private fun parseAllocateSuccess(data: ByteArray, txId: ByteArray): Boolean {
        if (data.size < 20) return false
        val bb = ByteBuffer.wrap(data)
        val type = bb.short.toInt() and 0xFFFF
        bb.short // skip the 2-byte STUN header length field
        val cookie = bb.int
        val respTx = ByteArray(12).also { bb.get(it) }
        return cookie == MAGIC_COOKIE && respTx.contentEquals(txId) && type == 0x0103
    }

    private fun buildAllocateRequest(
        txId: ByteArray,
        username: String?,
        realm: String?,
        nonce: String?,
        integrityKey: ByteArray?,
    ): ByteArray {
        val attrs = ArrayList<ByteArray>()
        // RFC 5766 §14.4: Allocate MUST include REQUESTED-TRANSPORT (UDP=17).
        attrs += stunAttr(0x0019, ByteBuffer.allocate(4).put(17).array())
        if (username != null) attrs += stunAttr(0x0006, username.toByteArray())   // USERNAME
        if (realm != null) attrs += stunAttr(0x0014, realm.toByteArray())         // REALM
        if (nonce != null) attrs += stunAttr(0x0015, nonce.toByteArray())         // NONCE

        val body = attrs.fold(ByteArray(0)) { acc, a -> acc + a }
        val miIndex = if (integrityKey != null) body.size else -1
        var finalBody = body

        if (integrityKey != null) {
            // Placeholder for MESSAGE-INTEGRITY (0x0008) — 20 bytes HMAC-SHA1.
            val miPlaceholder = ByteBuffer.allocate(4 + 20)
                .putShort(0x0008.toShort()).putShort(20)
                .put(ByteArray(20)).array()
            finalBody = body + miPlaceholder
        }

        val header = ByteBuffer.allocate(20)
        header.putShort(0x0003.toShort())      // Allocate request
        header.putShort(finalBody.size.toShort())
        header.putInt(MAGIC_COOKIE)
        header.put(txId)

        val msg = header.array() + finalBody

        if (integrityKey != null && miIndex >= 0) {
            // RFC 5389 §15.4: the HMAC is over the STUN message up to but NOT
            // including the MESSAGE-INTEGRITY attribute itself, while the STUN
            // header length field covers the whole message including the MI TLV.
            // msg.size - 24 = header + attributes + MI attribute header only,
            // so exclude the entire 24-byte MI TLV (4 header + 20 value).
            val hmacInput = msg.copyOf(msg.size - 24)
            val hmac = hmacSha1(integrityKey, hmacInput)
            // Write the real HMAC into the placeholder.
            System.arraycopy(hmac, 0, msg, msg.size - 20, 20)
        }
        return msg
    }

    private fun stunAttr(type: Int, value: ByteArray): ByteArray {
        val padded = if (value.size % 4 == 0) value else value + ByteArray(4 - value.size % 4)
        return ByteBuffer.allocate(4 + padded.size)
            .putShort(type.toShort()).putShort(value.size.toShort()).put(padded).array()
    }

    private fun md5(s: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    companion object {
        private const val MAGIC_COOKIE = 0x2112A442
    }
}
