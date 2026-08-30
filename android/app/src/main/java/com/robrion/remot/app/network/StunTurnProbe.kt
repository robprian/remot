package com.robrion.remot.network

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
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
    /** Transport the successful reply arrived over: "udp" | "tcp" | null. */
    val transport: String? = null,
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
 *   2. A real STUN Binding request/response (proves the server speaks STUN and
 *      is reachable) — measured RTT.
 *   3. A real TURN Allocate handshake (401 realm/nonce challenge then
 *      authenticated Allocate with the short-lived credentials the signaling
 *      server issued) — proves the TURN relay actually accepts allocations.
 *
 * It tries **UDP first, then falls back to TCP**. Many mobile/carrier networks
 * throttle or block UDP to arbitrary ports while TCP works, which is the most
 * common cause of "STUN/TURN unreachable" in the app even though the server is
 * perfectly healthy; since coturn listens on 3478 TCP for WebRTC, probing over
 * TCP gives an honest online answer where UDP alone would time out.
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
            resolveIpv4First()
        } catch (e: Exception) {
            Log.w(logTag, "DNS failed for $host: ${e.message}")
            return StunTurnResult(dnsOk = false, stunOk = false, turnOk = false, error = "dns")
        }

        // Retry UDP a few times before giving up: carrier/CGNAT NATs often drop
        // the very first outbound datagram while the NAT binding is being
        // established, so a single attempt can false-negative.
        var udp = StunTurnResult(dnsOk = true, stunOk = false, turnOk = false, error = "timeout")
        val udpAttempts = 3
        for (i in 1..udpAttempts) {
            udp = probeTransport(UdpTransport(address, port), timeoutMs)
            // Stop early once UDP actually answered (or failed for a definitive
            // reason that TCP cannot fix).
            if (udp.stunOk || udp.error !in setOf("timeout", "stun")) break
        }
        // Only fall back to TCP when UDP genuinely failed at the STUN layer
        // (timeout / stun). If UDP already answered STUN+TURN, or it merely
        // lacked credentials, there is nothing to retry TCP for.
        if (udp.stunOk || udp.error in setOf("no-credentials", "dns")) return udp
        val tcp = probeTransport(TcpTransport(address, port), timeoutMs)
        // Prefer the TCP result only if it actually moved the needle; otherwise
        // keep the original UDP detail (e.g. its specific timeout) for diagnostics.
        return if (tcp.stunOk || tcp.turnOk) tcp else udp
    }

    /** One probe run over a concrete transport (UDP datagram or TCP stream). */
    private fun probeTransport(io: StunTurnIo, timeoutMs: Long): StunTurnResult {
        if (!io.open(timeoutMs)) {
            return StunTurnResult(dnsOk = true, stunOk = false, turnOk = false, transport = io.name(), error = "timeout")
        }
        return try {
            // --- STUN binding ---
            val bindingStart = System.nanoTime()
            val bindingTx = randomTransactionId()
            val bindingReq = buildBindingRequest(bindingTx)
            val resp = io.exchange(bindingReq, timeoutMs)
            val stunOk = resp != null && parseBindingResponse(resp, bindingTx)
            if (!stunOk) {
                return StunTurnResult(
                    dnsOk = true, stunOk = false, turnOk = false, transport = io.name(),
                    error = "stun", latencyMs = null
                )
            }
            val stunRttMs = (System.nanoTime() - bindingStart) / 1_000_000

            // --- TURN allocate (requires credentials) ---
            if (username == null || password == null) {
                // No credentials available (server not reachable / not registered):
                // STUN worked, TURN can't be authenticated → report honestly.
                return StunTurnResult(
                    dnsOk = true, stunOk = true, turnOk = false, transport = io.name(),
                    latencyMs = stunRttMs, error = "no-credentials"
                )
            }
            val turnStart = System.nanoTime()
            val turnTx = randomTransactionId()
            val turnOk = allocate(io, turnTx, timeoutMs)
            val turnRttMs = if (turnOk) (System.nanoTime() - turnStart) / 1_000_000 else null
            return StunTurnResult(
                dnsOk = true, stunOk = true, turnOk = turnOk, transport = io.name(),
                latencyMs = turnRttMs ?: stunRttMs,
                error = if (turnOk) null else "turn"
            )
        } catch (e: SocketTimeoutException) {
            StunTurnResult(dnsOk = true, stunOk = false, turnOk = false, transport = io.name(), error = "timeout")
        } catch (e: Exception) {
            Log.w(logTag, "probe(${io.name()}) failed: ${e.message}")
            StunTurnResult(dnsOk = true, stunOk = false, turnOk = false, transport = io.name(), error = e.message)
        } finally {
            io.close()
        }
    }

    /**
     * Resolves the host preferring IPv4. Some TURN hostnames publish AAAA (IPv6)
     * records that aren't routable from the device's network; preferring the
     * IPv4 address avoids a probe that times out on the IPv6 leg.
     */
    private fun resolveIpv4First(): InetAddress {
        val all = InetAddress.getAllByName(host)
        return all.firstOrNull { it.hostAddress?.contains(":") == false }
            ?: all.first()
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
    private fun allocate(io: StunTurnIo, txId: ByteArray, timeoutMs: Long): Boolean {
        // Attempt 1: unauthenticated Allocate (type 0x0003)
        val req1 = buildAllocateRequest(txId, username = null, realm = null, nonce = null, integrityKey = null)
        val resp1 = io.exchange(req1, timeoutMs) ?: return false
        val challenge = parseAllocateChallenge(resp1, txId) ?: return false
        if (challenge.realm == null || challenge.nonce == null) return false

        // Attempt 2: authenticated Allocate
        val key = md5("$username:${challenge.realm}:$password")
        val req2 = buildAllocateRequest(
            txId, username = username, realm = challenge.realm, nonce = challenge.nonce, integrityKey = key
        )
        val resp2 = io.exchange(req2, timeoutMs) ?: return false
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

        if (integrityKey != null) {
            // RFC 5389 §15.4: the HMAC is over the STUN message up to but NOT
            // including the MESSAGE-INTEGRITY attribute itself, while the STUN
            // header length field covers the whole message including the MI TLV.
            val hmacInput = msg.copyOf(msg.size - 24)
            val hmac = hmacSha1(integrityKey, hmacInput)
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

/** Common contract for one probe transport (UDP datagram or TCP stream). */
private interface StunTurnIo {
    fun name(): String
    fun open(timeoutMs: Long): Boolean
    /** Send [payload] and return the single reply message, or null on failure. */
    fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray?
    fun close()
}

/** UDP transport: a connected DatagramSocket; each exchange = one datagram out, one in. */
private class UdpTransport(
    private val address: InetAddress,
    private val port: Int,
) : StunTurnIo {
    private var socket: DatagramSocket? = null

    override fun name() = "udp"

    override fun open(timeoutMs: Long): Boolean = try {
        DatagramSocket().also { s ->
            socket = s
            s.soTimeout = timeoutMs.toInt()
            s.connect(InetSocketAddress(address, port))
        }
        true
    } catch (e: Exception) {
        false
    }

    override fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray? {
        val s = socket ?: return null
        s.send(DatagramPacket(payload, payload.size))
        val buf = ByteArray(1500)
        val pkt = DatagramPacket(buf, buf.size)
        s.receive(pkt)
        return pkt.data.copyOf(pkt.length)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}

/** TCP transport: a connected Socket; reads a full STUN message (framed by its length field). */
private class TcpTransport(
    private val address: InetAddress,
    private val port: Int,
) : StunTurnIo {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: InputStream? = null

    override fun name() = "tcp"

    override fun open(timeoutMs: Long): Boolean = try {
        Socket().also { s ->
            socket = s
            s.connect(InetSocketAddress(address, port), timeoutMs.toInt())
            s.soTimeout = timeoutMs.toInt()
            out = s.getOutputStream()
            input = s.getInputStream()
        }
        true
    } catch (e: Exception) {
        runCatching { socket?.close() }
        socket = null
        false
    }

    override fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray? {
        val o = out ?: return null
        val i = input ?: return null
        // STUN-over-TCP carries each message's length in its own header, so
        // write the request then read one complete STUN message.
        o.write(payload)
        o.flush()
        val header = ByteArray(20)
        readFully(i, header)
        val msgLen = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val body = ByteArray(msgLen)
        readFully(i, body)
        return header + body
    }

    private fun readFully(i: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = i.read(buf, off, buf.size - off)
            if (n < 0) throw SocketException("socket closed")
            off += n
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        out = null
        input = null
    }
}