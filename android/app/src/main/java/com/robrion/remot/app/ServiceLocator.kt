package com.robrion.remot

import android.content.Context
import com.robrion.remot.identity.DeviceIdentity
import com.robrion.remot.network.NetworkHealthRepository
import java.net.URI
import com.robrion.remot.session.SessionManager
import com.robrion.remot.signaling.SignalingClient
import com.robrion.remot.trust.PairingManager
import com.robrion.remot.trust.TrustStore
import com.robrion.remot.unattended.GrantStore
import com.robrion.remot.webrtc.WebRtcCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal manual DI. Holds the app-wide singletons and the single implementation
 * of [SignalingClient.Listener], fanning signaling events out to the SessionManager,
 * PairingManager, and whatever UI callbacks are currently registered.
 *
 * A larger app would use Hilt/Koin; this keeps the wiring visible in one file.
 */
object ServiceLocator : SignalingClient.Listener {

    lateinit var appContext: Context; private set
    lateinit var core: WebRtcCore; private set
    lateinit var trust: TrustStore; private set
    lateinit var grants: GrantStore; private set
    lateinit var signaling: SignalingClient; private set
    lateinit var session: SessionManager; private set
    lateinit var pairing: PairingManager; private set
    lateinit var networkHealth: NetworkHealthRepository; private set
    lateinit var deviceId: String; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- UI-facing callbacks (set by MainViewModel; safe no-ops by default) ----
    var uiOnSessionCode: (String) -> Unit = {}
    var uiOnJoinRequest: (controllerId: String, unattended: Boolean, grantId: String?) -> Unit = { _, _, _ -> }
    var uiOnConsentResult: (accepted: Boolean, hostId: String) -> Unit = { _, _ -> }
    var uiOnJoinFailed: (String) -> Unit = {}
    var uiOnSafetyNumber: (peerPubB64: String, number: String) -> Unit = { _, _ -> }

    fun init(context: Context, signalingUrl: String) {
        appContext = context.applicationContext
        DeviceIdentity.ensureCreated()
        deviceId = DeviceIdentity.deviceId()

        core = WebRtcCore(appContext)
        trust = TrustStore.create(appContext)
        grants = GrantStore.create(appContext)
        signaling = SignalingClient(appContext, signalingUrlCandidates(signalingUrl), deviceId, this)
        session = SessionManager(appContext, core, signaling, trust, scope)
        pairing = PairingManager(signaling, trust)
        networkHealth = NetworkHealthRepository(appContext, scope)

        signaling.connect()
    }

    // ---- SignalingClient.Listener ----
    override fun onRegistered(iceServers: JSONArray) {
        session.updateIceServers(iceServers)
        networkHealth.onIceServers(session.iceServers)
    }
    override fun onRegisterFailed(reason: String) {
        android.util.Log.w("RemotApp", "signaling registration rejected by server: $reason")
    }
    override fun onTurnCredentials(iceServers: JSONArray) {
        session.updateIceServers(iceServers)
        networkHealth.onIceServers(session.iceServers)
    }
    override fun onSessionCode(code: String) = uiOnSessionCode(code)
    override fun onJoinRequest(controllerId: String, unattended: Boolean, grantId: String?) =
        uiOnJoinRequest(controllerId, unattended, grantId)
    override fun onJoinFailed(reason: String) = uiOnJoinFailed(reason)
    override fun onConsent(accepted: Boolean, hostId: String) {
        if (accepted) session.startController(hostId)
        uiOnConsentResult(accepted, hostId)
    }
    override fun onOffer(from: String, sdp: String, fpSig: String?) { session.onOffer(from, sdp, fpSig) }
    override fun onAnswer(from: String, sdp: String, fpSig: String?) { session.onAnswer(from, sdp, fpSig) }
    override fun onIce(from: String, mid: String, index: Int, cand: String) { session.onIce(mid, index, cand) }
    override fun onRestart(from: String) { session.onRestart() }
    override fun onHangup(from: String) { session.endIfPeer(from) }

    override fun onPairComplete(m: JSONObject) {
        pairing.onPairComplete(m).onSuccess { number ->
            uiOnSafetyNumber(m.getString("controllerPub"), number)
        }
    }
    override fun onPairAck(m: JSONObject) {
        // hostName is best-effort here; a fuller impl would carry it through pairing state.
        pairing.onPairAck(m, "Paired device").onSuccess { number ->
            pairing.pendingPeerPubB64()?.let { peerPub -> uiOnSafetyNumber(peerPub, number) }
        }
    }
    override fun onAuthChallenge(from: String, nonceB64: String) {
        // Prove our identity over the challenge nonce. The nonce MUST be echoed
        // verbatim in the auth-response or the server rejects registration with
        // `auth-failed` (server requires m.nonce === challenge nonce).
        val sig = DeviceIdentity.sign(com.robrion.remot.crypto.Crypto.unb64(nonceB64))
        signaling.sendAuthResponse(from, nonceB64, com.robrion.remot.crypto.Crypto.b64(sig))
    }
    override fun onAuthResponse(from: String, sigB64: String) {
        // Host-side verification is handled by whoever issued the challenge
        // (see UnattendedHostService / consent flow). No-op at the locator level.
    }

    /**
     * Builds the ordered list of signaling endpoints the client tries in turn.
     *
     * TLS (wss://) is preferred so signaling is not sent in cleartext. For each
     * host we try the :8443 TLS endpoint first, then the legacy ws:// :8080 form
     * (kept for very old servers), then rotate across the fallback hosts. This
     * keeps signaling reachable through carrier NAT / partial routes / a primary
     * outage without hardcoding any address in source (addresses come from
     * GitHub Secrets at build time; the TLS path trusts the embedded Remot CA
     * for self-signed backup servers and the system store for Let's Encrypt).
     *
     * A `wss://` form is NEVER emitted for a port that only serves plain `ws://`
     * (e.g. :8080) — the server would answer "Unable to parse TLS packet header".
     * A `wss://<host>:<originalPort>` is only kept when the URL itself was `wss`.
     */
    private fun signalingUrlCandidates(primary: String): List<String> =
        signalingUrlCandidates(
            primary = primary,
            serverIp = BuildConfig.SERVER_IP,
            serverUrlAlt = BuildConfig.SERVER_URL_ALT,
            serverIpAlt = BuildConfig.SERVER_IP_ALT,
        )
}

/**
 * Pure endpoint-list builder (extracted for unit tests).
 *
 * Returns a de-duplicated, ordered list of signaling URLs the client tries in
 * turn. TLS `wss://:8443` always comes first for every host; the plain `ws://`
 * form is a legacy fallback. A `wss://<host>:<originalPort>` entry is only kept
 * when the URL itself was already TLS (wss://) — never for a plain ws:// URL,
 * since TLS against a plain listener fails with "Unable to parse TLS packet
 * header".
 */
fun signalingUrlCandidates(
    primary: String,
    serverIp: String,
    serverUrlAlt: String,
    serverIpAlt: String,
): List<String> {
    val out = LinkedHashSet<String>()

    /** Adds wss:// on the TLS port (8443), then ws:// on the plain port. */
    fun candidates(host: String, originalPort: Int, originalScheme: String, path: String) {
        val plainPort = if (originalPort == 80 || originalPort == 443 || originalPort == 8443) 8080 else originalPort
        // Prefer TLS :8443 for this host, then a ws:// form. Keep a wss:// on
        // the URL's own port ONLY when that URL was already TLS (wss://).
        out.add("wss://$host:8443$path")
        out.add("ws://$host:$plainPort$path")
        if (originalScheme == "wss" && originalPort != 8443) {
            out.add("wss://$host:$originalPort$path")
        }
    }

    try {
        val u = URI(primary)
        val host = u.host ?: return out.toList()
        val port = if (u.port in 1..65535) u.port else if (u.scheme == "wss") 443 else 80
        val path = u.rawPath?.takeIf { it.isNotBlank() } ?: ""
        val scheme = u.scheme ?: "ws"

        candidates(host, port, scheme, path)
        if (serverIp.isNotBlank()) candidates(serverIp, port, scheme, path)

        // Backup/alternate endpoints from GitHub Secrets.
        if (serverUrlAlt.isNotBlank()) {
            try {
                val av = URI(serverUrlAlt)
                val aside = av.host ?: serverUrlAlt
                val aport = if (av.port in 1..65535) av.port else 8080
                candidates(aside, aport, av.scheme ?: "ws", av.rawPath?.takeIf { it.isNotBlank() } ?: "")
            } catch (e2: Exception) {
                out.add(serverUrlAlt)
            }
        }
        if (serverIpAlt.isNotBlank()) candidates(serverIpAlt, port, scheme, path)
    } catch (e: Exception) {
        // fall back to only the primary (plus any alternate URL that parsed)
        out.add(primary)
        if (serverUrlAlt.isNotBlank()) out.add(serverUrlAlt)
    }
    return out.toList()
}
