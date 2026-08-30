package com.robrion.remot.signaling

import android.os.Handler
import android.os.Looper
import com.robrion.remot.identity.DeviceIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * WebSocket signaling client with automatic reconnect + re-register. Registration
 * is AUTHENTICATED: the client presents its device public key and signs the
 * server's nonce challenge, proving it owns the claimed deviceId. Delivers
 * decoded messages to a [Listener]. All send helpers are fire-and-forget JSON.
 */
class SignalingClient(
    private val context: android.content.Context,
    private val urls: List<String>,
    private val deviceId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onRegistered(iceServers: JSONArray) {}
        fun onRegisterFailed(reason: String) {}
        fun onSessionCode(code: String) {}
        fun onJoinRequest(controllerId: String, unattended: Boolean, grantId: String?) {}
        fun onJoinPending(hostId: String, unattended: Boolean) {}
        fun onJoinFailed(reason: String) {}
        fun onConsent(accepted: Boolean, hostId: String) {}
        fun onOffer(from: String, sdp: String, fpSig: String?) {}
        fun onAnswer(from: String, sdp: String, fpSig: String?) {}
        fun onIce(from: String, mid: String, index: Int, cand: String) {}
        fun onRestart(from: String) {}
        fun onHangup(from: String) {}
        fun onTurnCredentials(iceServers: JSONArray) {}
        // pairing + per-session auth
        fun onPairComplete(m: JSONObject) {}
        fun onPairAck(m: JSONObject) {}
        fun onAuthChallenge(from: String, nonceB64: String) {}
        fun onAuthResponse(from: String, sigB64: String) {}
    }

    @Volatile var isConnected = false; private set

    /** True once the server accepted our signed registration (heartbeat may run). */
    @Volatile var isRegistered = false; private set

    /** True when the server permanently rejected our identity (no auto-reconnect). */
    @Volatile var isAuthFailed = false; private set

    /** Last measured signaling ping/pong round-trip (app-level heartbeat), ms. */
    val signalingLatencyMs: Long? get() = heartbeat.latencyMs

    /** Pure heartbeat state machine — used by [signalingLatencyMs] and tests. */
    private val heartbeat = HeartbeatTracker(
        nowMs = { System.currentTimeMillis() },
        schedule = { r, delay -> main.postDelayed(r, delay) },
        cancel = { r -> main.removeCallbacks(r) },
    ).apply {
        onPing = { ts -> send(JSONObject().put("type", "ping").put("ts", ts)) }
        onDead = {
            SignalingDebugLog.record(signalingUrl, "HEARTBEAT-FAILED", "$missedPongs missed pongs")
            lastConnectError = "heartbeat timeout ($missedPongs missed pongs) @ $signalingUrl"
            ws?.cancel()
            ws = null
        }
    }

    /** The endpoint this client is CURRENTLY using — surfaced in Diagnostics. */
    val signalingUrl: String get() = urls[urlIndex % urls.size]

    /** True when the active connection is using a fallback endpoint (not the first). */
    val usingFallbackUrl: Boolean get() = (urlIndex % urls.size) != 0

    private var urlIndex = 0

    /** Last connection failure reason (cleared on a successful connect). */
    @Volatile var lastConnectError: String? = null; private set

    var onReconnected: (() -> Unit)? = null

    // Prefer IPv4 when resolving the signaling host. Some deployments put the
    // server behind a DNS with AAAA (IPv6) records that aren't actually routable
    // from mobile carriers — e.g. turn.robrion.net resolves to IPv6 first, then
    // IPv4, and the IPv6 leg can abort ("Software caused connection abort")
    // before OkHttp falls through to IPv4. Ordering IPv4 first fixes that.
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e2: Exception) {
                    emptyList()
                }
            }
            return all.sortedBy { it.hostAddress?.contains(":") == true } // IPv6 last
        }
    }

    private val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        // Trust the embedded Remot CA (for the self-signed secondary wss endpoint)
        // IN ADDITION to the Android system trust store, so a Let's Encrypt primary
        // (which validates against the system store) still works unchanged. The
        // hostname is still checked normally; we only add our CA as an extra
        // anchor — we never accept untrusted chains from strangers.
        val ssl = trustedSslContext()
        if (ssl != null) {
            builder.sslSocketFactory(ssl.first, ssl.second)
        }
        builder.build()
    }

    /**
     * Builds an [SSLSocketFactory] + [X509TrustManager] whose trust anchors are the
     * Android system CAs UNION the embedded Remot CA (res/raw/remot_ca.pem). Returns
     * null when the CA resource can't be read, in which case OkHttp uses its default
     * (system-only) trust — a cleartext-only/older install stays fully functional.
     */
    private fun trustedSslContext(): Pair<SSLSocketFactory, X509TrustManager>? = runCatching {
        val systemTm = defaultTrustManager()
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType())
        anchors.load(null)
        systemTm.acceptedIssuers.forEach { anchors.setCertificateEntry(it.subjectDN.name, it) }

        val remotCa = loadRemotCaCert()
        if (remotCa != null) {
            anchors.setCertificateEntry("remot-ca", remotCa)
        } else {
            return null // nothing to pin — use OkHttp defaults
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(anchors)
        val tm = tmf.trustManagers.toList().filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<X509TrustManager>(tm), SecureRandom())
        ctx.socketFactory to tm
    }.getOrNull()

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.toList().filterIsInstance<X509TrustManager>().first()
    }

    /** Reads our embedded Remot CA cert, or null if absent/unreadable. */
    private fun loadRemotCaCert(): X509Certificate? = runCatching {
        val input = context.resources.openRawResource(com.robrion.remot.R.raw.remot_ca)
        val factory = CertificateFactory.getInstance("X.509")
        input.use { factory.generateCertificate(it) as X509Certificate }
    }.getOrNull()

    private var ws: WebSocket? = null
    private var reconnectAttempt = 0
    private var closedByUser = false
    private val main = Handler(Looper.getMainLooper())

    fun connect() {
        closedByUser = false
        isAuthFailed = false
        SignalingDebugLog.record(currentUrl(), "CONNECTING")
        ws = client.newWebSocket(Request.Builder().url(currentUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isRegistered = false
                lastConnectError = null
                reconnectAttempt = 0
                SignalingDebugLog.record(signalingUrl, "CONNECTED")
                onReconnected?.invoke()
                // Present the public key; the server verifies deviceId == sha256(pub),
                // challenges us with a nonce (see onAuthChallenge), and only then
                // marks this socket registered.
                send(
                    JSONObject()
                        .put("type", "register")
                        .put("deviceId", deviceId)
                        .put("pubKeyB64", DeviceIdentity.publicKeyB64())
                )
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(JSONObject(text))
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Surface WHY we can't reach signaling (e.g. cleartext-blocked,
                // DNS, TLS, or the server is down) so it's visible in Diagnostics.
                val reason = when {
                    !t.message.isNullOrBlank() -> t.message.orEmpty()
                    response != null && response.code > 0 -> "HTTP " + response.code
                    else -> "connection failed"
                }
                stopHeartbeat()
                isConnected = false
                isRegistered = false
                lastConnectError = "$reason @ $signalingUrl"
                SignalingDebugLog.record(signalingUrl, "FAILED", reason)
                rotateEndpoint()
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                isConnected = false
                isRegistered = false
                if (lastConnectError == null) lastConnectError = reason.ifBlank { "closed ($code)" }
                SignalingDebugLog.record(signalingUrl, "CLOSED", reason.ifBlank { "code $code" })
                rotateEndpoint()
                scheduleReconnect()
            }
        })
    }

    private fun rotateEndpoint() {
        if (urls.size > 1) urlIndex = (urlIndex + 1) % urls.size
    }

    private fun currentUrl(): String = urls[urlIndex % urls.size]

    private fun close() {
        closedByUser = true
        isConnected = false
        ws?.close(1000, "bye")
        ws = null
    }

    private fun scheduleReconnect() {
        isConnected = false
        isRegistered = false
        if (closedByUser || isAuthFailed) return
        val delayMs = minOf(1000L * (1 shl reconnectAttempt.coerceAtMost(4)), 15_000L)
        reconnectAttempt++
        main.postDelayed({ if (!closedByUser && !isAuthFailed) connect() }, delayMs)
    }

    suspend fun reconnectAndAwait(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        if (!isConnected) connect()
        while (!isConnected) delay(100)
        true
    } ?: false

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "registered" -> {
                isRegistered = true
                SignalingDebugLog.record(signalingUrl, "REGISTERED")
                listener.onRegistered(m.optJSONArray("iceServers") ?: JSONArray())
                startHeartbeat()
            }
            "pong" -> heartbeat.onPong()
            "register-failed" -> {
                val reason = m.optString("reason", "unknown")
                // Registration was rejected: the socket is dead. Close it ourselves
                // (the server closes too) and NEVER keep heartbeat running on an
                // unauthenticated connection.
                stopHeartbeat()
                isConnected = false
                isRegistered = false
                isAuthFailed = true
                lastConnectError = "registration rejected: $reason @ $signalingUrl"
                SignalingDebugLog.record(signalingUrl, "REGISTER-FAILED", reason)
                SignalingDebugLog.record(signalingUrl, "HEARTBEAT-STOP", "registration failed")
                listener.onRegisterFailed(reason)
                ws?.close(1000, "registration-rejected")
                ws = null
            }
            "session-code" -> listener.onSessionCode(m.getString("code"))
            "join-request" -> listener.onJoinRequest(
                m.getString("controllerId"), m.optBoolean("unattended", false),
                if (m.isNull("grantId")) null else m.optString("grantId")
            )
            "join-pending" -> listener.onJoinPending(m.getString("hostId"), m.optBoolean("unattended", false))
            "join-failed" -> listener.onJoinFailed(m.getString("reason"))
            "consent" -> listener.onConsent(m.getBoolean("accepted"), m.getString("hostId"))
            "offer" -> listener.onOffer(m.getString("from"), m.getString("sdp"), optStringOrNull(m, "fpSig"))
            "answer" -> listener.onAnswer(m.getString("from"), m.getString("sdp"), optStringOrNull(m, "fpSig"))
            "ice" -> listener.onIce(m.getString("from"), m.getString("mid"), m.getInt("index"), m.getString("cand"))
            "restart" -> listener.onRestart(m.getString("from"))
            "hangup" -> listener.onHangup(m.getString("from"))
            "turn-credentials" -> listener.onTurnCredentials(m.optJSONArray("iceServers") ?: JSONArray())
            "pair-complete" -> listener.onPairComplete(m)
            "pair-ack" -> listener.onPairAck(m)
            "auth-challenge" -> listener.onAuthChallenge(m.optString("from", ""), m.getString("nonce"))
            "auth-response" -> listener.onAuthResponse(m.getString("from"), m.getString("sig"))
        }
    }

    // ---- send helpers ----
    fun send(o: JSONObject) { ws?.send(o.toString()) }
    fun hostOpen() = send(JSONObject().put("type", "host-open"))
    fun join(code: String) = send(JSONObject().put("type", "join").put("code", code))
    fun joinPaired(hostId: String) = send(JSONObject().put("type", "join").put("hostId", hostId))
    fun consent(controllerId: String, accepted: Boolean) =
        send(JSONObject().put("type", "consent").put("controllerId", controllerId).put("accepted", accepted))
    fun requestTurn() = send(JSONObject().put("type", "turn-credentials"))
    fun reportToken(token: String) = send(JSONObject().put("type", "report-token").put("fcmToken", token))
    fun requestRestart(to: String) = send(JSONObject().put("type", "restart").put("to", to))

    fun sendOffer(to: String, sdp: String, fpSig: String) =
        send(JSONObject().put("type", "offer").put("to", to).put("sdp", sdp).put("fpSig", fpSig))
    fun sendAnswer(to: String, sdp: String, fpSig: String) =
        send(JSONObject().put("type", "answer").put("to", to).put("sdp", sdp).put("fpSig", fpSig))
    fun sendIce(to: String, mid: String?, index: Int, cand: String) =
        send(JSONObject().put("type", "ice").put("to", to).put("mid", mid).put("index", index).put("cand", cand))

    fun sendAuthChallenge(to: String, nonceB64: String) =
        send(JSONObject().put("type", "auth-challenge").put("to", to).put("nonce", nonceB64))

    /**
     * Answer the server's registration (or peer-relayed) auth challenge. The
     * server REQUIRES the challenge `nonce` to be echoed verbatim — a
     * registration `auth-response` without it is rejected with `auth-failed`
     * (the root cause of "Signaling unreachable / auth-failed" on device).
     */
    fun sendAuthResponse(to: String, nonceB64: String, sigB64: String) {
        send(SignalingMessages.authResponse(to, nonceB64, sigB64))
    }

    // ---- app-level heartbeat (runs ONLY after registration) ----

    private fun startHeartbeat() {
        if (!heartbeat.started) {
            SignalingDebugLog.record(signalingUrl, "HEARTBEAT-START")
        }
        heartbeat.start()
    }

    private fun stopHeartbeat() {
        heartbeat.stop()
    }

    fun clearRegistrationState() {
        stopHeartbeat()
        isConnected = false
        isRegistered = false
        isAuthFailed = false
    }
}

/** Returns the string field, or null when absent/JSON-null (avoids optString(name, null)). */
private fun optStringOrNull(o: JSONObject, name: String): String? =
    if (o.has(name) && !o.isNull(name)) o.getString(name) else null
