package com.robrion.remot.session

import android.content.Context
import com.robrion.remot.crypto.Crypto
import com.robrion.remot.host.InputRouter
import com.robrion.remot.signaling.SignalingClient
import com.robrion.remot.trust.TrustStore
import com.robrion.remot.webrtc.LinkState
import com.robrion.remot.webrtc.RtcSession
import com.robrion.remot.webrtc.WebRtcCore
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/**
 * Owns the single active RtcSession and wires it to signaling, trust, and input.
 * Both roles funnel through here so the app has one place that knows "am I in a
 * session, and as what."
 */
class SessionManager(
    private val appContext: Context,
    private val core: WebRtcCore,
    private val signaling: SignalingClient,
    private val trust: TrustStore,
    private val scope: CoroutineScope,
) {
    @Volatile var iceServers: List<PeerConnection.IceServer> =
        WebRtcCore.parseIceServers(JSONArray())
        private set

    @Volatile var activeGrantId: String? = null
    @Volatile private var activePeerId: String? = null
    private var session: RtcSession? = null

    var onRemoteVideo: ((VideoTrack) -> Unit)? = null
    var onLinkState: ((LinkState) -> Unit)? = null
    var onIceRoute: ((String?) -> Unit)? = null

    fun updateIceServers(arr: JSONArray) {
        iceServers = WebRtcCore.parseIceServers(arr)
        session?.updateIceServers(iceServers)
    }

    private fun newSession(): RtcSession = RtcSession(
        appContext, core.eglBase, core.factory, signaling, scope, iceServers
    ).apply {
        peerPublicKeyProvider = { peerId -> resolvePeerPub(peerId) }
        onRemoteVideo = { this@SessionManager.onRemoteVideo?.invoke(it) }
        onLinkState = { this@SessionManager.onLinkState?.invoke(it) }
        onIceRoute = { this@SessionManager.onIceRoute?.invoke(it) }
        onControlMessage = { InputRouter.dispatch(it) }
    }

    /** HOST: begin sharing to a controller with an already-built screen track. */
    fun startHost(controllerId: String, screenTrack: VideoTrack, grantId: String? = null) {
        endNow() // tear down any prior session before replacing it (avoids leaks)
        activePeerId = controllerId
        activeGrantId = grantId
        session = newSession().also { it.startAsHost(controllerId, screenTrack) }
    }

    /** CONTROLLER: begin receiving from a host. */
    fun startController(hostId: String) {
        endNow()
        activePeerId = hostId
        session = newSession().also { it.startAsController(hostId) }
    }

    // ---- signaling fan-in (called by the app's Listener) ----
    fun onOffer(from: String, sdp: String, fpSig: String?) = session?.onRemoteOffer(from, sdp, fpSig)
    fun onAnswer(from: String, sdp: String, fpSig: String?) = session?.onRemoteAnswer(from, sdp, fpSig)
    fun onIce(mid: String, idx: Int, cand: String) = session?.onRemoteIce(mid, idx, cand)
    fun onRestart() = session?.onRestartRequested()

    fun sendControl(msg: JSONObject) = session?.sendControl(msg) ?: Unit

    fun endIfPeer(peerId: String) { if (activePeerId == peerId) endNow() }

    fun endNow() {
        session?.hangup()
        session = null
        activePeerId = null
        activeGrantId = null
    }

    private fun resolvePeerPub(peerId: String): ByteArray? {
        // peerId is the public-key id; find the paired identity whose pub hashes to it.
        return trust.all().firstOrNull {
            Crypto.publicKeyId(Crypto.unb64(it.peerPubKeyB64)) == peerId
        }?.let { Crypto.unb64(it.peerPubKeyB64) }
    }
}
