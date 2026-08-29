package com.robrion.remot

import android.content.Context
import com.robrion.remot.identity.DeviceIdentity
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
        signaling = SignalingClient(signalingUrl, deviceId, this)
        session = SessionManager(appContext, core, signaling, trust, scope)
        pairing = PairingManager(signaling, trust)

        signaling.connect()
    }

    // ---- SignalingClient.Listener ----
    override fun onRegistered(iceServers: JSONArray) { session.updateIceServers(iceServers) }
    override fun onRegisterFailed(reason: String) {
        android.util.Log.w("RemotApp", "signaling registration rejected by server: $reason")
    }
    override fun onTurnCredentials(iceServers: JSONArray) { session.updateIceServers(iceServers) }
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
        // Prove our identity over the challenge nonce.
        val sig = DeviceIdentity.sign(com.robrion.remot.crypto.Crypto.unb64(nonceB64))
        signaling.sendAuthResponse(from, com.robrion.remot.crypto.Crypto.b64(sig))
    }
    override fun onAuthResponse(from: String, sigB64: String) {
        // Host-side verification is handled by whoever issued the challenge
        // (see UnattendedHostService / consent flow). No-op at the locator level.
    }
}
