package com.remot.app.signaling

import android.os.Handler
import android.os.Looper
import com.remot.app.identity.DeviceIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebSocket signaling client with automatic reconnect + re-register. Registration
 * is AUTHENTICATED: the client presents its device public key and signs the
 * server's nonce challenge, proving it owns the claimed deviceId. Delivers
 * decoded messages to a [Listener]. All send helpers are fire-and-forget JSON.
 */
class SignalingClient(
    private val url: String,
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
    var onReconnected: (() -> Unit)? = null

    private val client = OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var reconnectAttempt = 0
    private var closedByUser = false

    /** Set when the server rejects our identity (permanent); stops the reconnect loop. */
    @Volatile private var authFailed = false
    private val main = Handler(Looper.getMainLooper())

    fun connect() {
        closedByUser = false
        authFailed = false
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempt = 0
                // Present the public key; the server verifies deviceId == sha256(pub),
                // challenges us with a nonce (see onAuthChallenge), and only then
                // marks this socket registered.
                send(
                    JSONObject()
                        .put("type", "register")
                        .put("deviceId", deviceId)
                        .put("pubKeyB64", DeviceIdentity.publicKeyB64())
                )
                onReconnected?.invoke()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(JSONObject(text))
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect()
        })
    }

    fun close() {
        closedByUser = true
        isConnected = false
        ws?.close(1000, "bye")
        ws = null
    }

    private fun scheduleReconnect() {
        isConnected = false
        if (closedByUser || authFailed) return
        val delayMs = minOf(1000L * (1 shl reconnectAttempt.coerceAtMost(4)), 15_000L)
        reconnectAttempt++
        main.postDelayed({ if (!closedByUser && !authFailed) connect() }, delayMs)
    }

    suspend fun reconnectAndAwait(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        if (!isConnected) connect()
        while (!isConnected) delay(100)
        true
    } ?: false

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "registered" -> listener.onRegistered(m.optJSONArray("iceServers") ?: JSONArray())
            "register-failed" -> {
                isConnected = false
                authFailed = true
                listener.onRegisterFailed(m.optString("reason", "unknown"))
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
    fun sendAuthResponse(to: String, sigB64: String) =
        send(JSONObject().put("type", "auth-response").put("to", to).put("sig", sigB64))
}

/** Returns the string field, or null when absent/JSON-null (avoids optString(name, null)). */
private fun optStringOrNull(o: JSONObject, name: String): String? =
    if (o.has(name) && !o.isNull(name)) o.getString(name) else null
