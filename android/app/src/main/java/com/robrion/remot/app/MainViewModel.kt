package com.robrion.remot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.robrion.remot.host.RemoteInputService
import com.robrion.remot.network.EndpointState
import com.robrion.remot.network.NetworkHealth
import com.robrion.remot.services.ServiceState
import com.robrion.remot.services.ServiceStatus
import com.robrion.remot.session.SessionCodes
import com.robrion.remot.trust.PeerIdentity
import com.robrion.remot.update.ApkInstaller
import com.robrion.remot.update.UpdateChecker
import com.robrion.remot.update.UpdateInfoState
import com.robrion.remot.webrtc.LinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/** Screens the single-activity UI can show. */
enum class Screen { HOME, HOST_CODE, JOIN, SCAN, PAIRED, PAIR_QR, SAFETY_NUMBER, SESSION, SERVICES }

private const val JOIN_TIMEOUT_MS = 45_000L

/** In-app update check throttle (ms) — keep GitHub API usage gentle. */
private const val UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L

/**
 * UI state + intent handling. Bridges Compose to ServiceLocator; keeps only
 * view-facing state, delegating real work to SessionManager / PairingManager /
 * NetworkHealthRepository.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    var screen by mutableStateOf(Screen.HOME); private set
    var sessionCode by mutableStateOf<String?>(null); private set
    var joinError by mutableStateOf<String?>(null); private set
    var linkState by mutableStateOf(LinkState.CONNECTING); private set

    // pending incoming controller awaiting host consent
    var pendingControllerId by mutableStateOf<String?>(null); private set
    var pendingUnattended by mutableStateOf(false); private set
    var pendingGrantId by mutableStateOf<String?>(null); private set

    // pairing confirmation
    var safetyNumber by mutableStateOf<String?>(null); private set
    var safetyPeerPub by mutableStateOf<String?>(null); private set

    var remoteVideo by mutableStateOf<VideoTrack?>(null); private set

    // pairing QR (host side of the authenticated pairing exchange)
    var pairingQr by mutableStateOf<String?>(null); private set

    // controller-side connection progress
    var connecting by mutableStateOf(false); private set   // dialed, awaiting host consent
    private var lastCode: String? = null
    private var joinTimeoutJob: Job? = null

    // ---- service states (refreshed from REAL system state on demand) ----
    var accessibilityState by mutableStateOf(ServiceState.INSTALLED); private set
    var notificationState by mutableStateOf(ServiceState.INSTALLED); private set

    // ---- infrastructure health (single source: NetworkHealthRepository) ----
    var networkHealth by mutableStateOf(NetworkHealth()); private set

    // ---- active session ICE route ("host"/"srflx"/"relay"/null) ----
    var iceRoute by mutableStateOf<String?>(null); private set

    // ---- in-app update (GitHub release) ----
    var updateState by mutableStateOf<UpdateInfoState?>(null); private set
    private var updateDismissed = false
    private var lastUpdateCheckMs = 0L
    private val updateHttp by lazy { OkHttpClient() }

    val eglBase: EglBase get() = ServiceLocator.core.eglBase

    /** Legacy convenience — Home screen quick check; full state in [accessibilityState]. */
    val accessibilityEnabled: Boolean get() = RemoteInputService.isEnabled(getApplication())

    /** Human-friendly ID of this device, shown on Home. */
    val deviceFingerprint: String
        get() = ServiceLocator.deviceId.take(16).chunked(4).joinToString("-").uppercase()

    fun pairedPeers(): List<PeerIdentity> = ServiceLocator.trust.all()

    init {
        ServiceLocator.uiOnSessionCode = { code -> sessionCode = code; screen = Screen.HOST_CODE }
        ServiceLocator.uiOnJoinRequest = { controllerId, unattended, grantId ->
            pendingControllerId = controllerId
            pendingUnattended = unattended
            pendingGrantId = grantId
        }
        ServiceLocator.uiOnConsentResult = { accepted, _ ->
            cancelJoinTimeout()
            connecting = false
            if (accepted) {
                screen = Screen.SESSION
            } else {
                joinError = "declined"
                screen = Screen.JOIN
            }
        }
        ServiceLocator.uiOnJoinFailed = { reason ->
            cancelJoinTimeout()
            connecting = false
            joinError = reason
            if (screen == Screen.SCAN) screen = Screen.JOIN
        }
        ServiceLocator.uiOnSafetyNumber = { peerPub, number ->
            safetyPeerPub = peerPub; safetyNumber = number; screen = Screen.SAFETY_NUMBER
        }
        ServiceLocator.session.onRemoteVideo = { remoteVideo = it }
        ServiceLocator.session.onLinkState = { linkState = it }
        ServiceLocator.session.onIceRoute = { iceRoute = it }

        // Mirror the repository's health state into Compose.
        ServiceLocator.networkHealth.onHealthChanged = { networkHealth = it }

        refreshServiceStates()
    }

    // ---- lifecycle: start/stop health polling ----
    /** Call from onResume: begin infrastructure polling + refresh service states. */
    fun onForeground() {
        refreshServiceStates()
        ServiceLocator.networkHealth.start()
        ServiceLocator.networkHealth.refreshNow()
        checkForUpdateIfStale()
    }

    /** Call from onPause: stop polling (never hammer the network in background). */
    fun onBackground() {
        ServiceLocator.networkHealth.stop()
    }

    /** Re-read the REAL Android system state for accessibility + notification listener. */
    fun refreshServiceStates() {
        accessibilityState = ServiceStatus.accessibilityState(getApplication())
        notificationState = ServiceStatus.notificationListenerState(getApplication())
    }

    fun refreshNetworkHealth() = ServiceLocator.networkHealth.refreshNow()

    // ---- navigation ----
    fun goHome() { cancelJoinTimeout(); connecting = false; screen = Screen.HOME }
    fun goJoin() { joinError = null; screen = Screen.JOIN }
    fun goScan() { joinError = null; screen = Screen.SCAN }
    fun goPaired() { screen = Screen.PAIRED }
    fun goServices() { refreshServiceStates(); screen = Screen.SERVICES }

    // ---- pairing (QR exchange) ----

    /**
     * Begin pairing as the HOST: build a signed pairing offer and show it as a
     * QR the other device scans. The other device completes the ECDH exchange,
     * both verify proofs, and the safety number screen appears on ack.
     */
    fun startPairing() {
        val offer = ServiceLocator.pairing.buildOffer(
            hostName = "Remot device (${deviceFingerprint})",
            relayUrl = ServiceLocator.signaling.signalingUrl,
        )
        pairingQr = offer.toJson()
        screen = Screen.PAIR_QR
    }

    /** The pairing QR payload (JSON PairingOffer), or null when not pairing. */
    fun pairingQrPayload(): String? = pairingQr

    fun cancelPairing() { pairingQr = null; goPaired() }

    /** QR payload for the current session code, or null if none yet. */
    fun sessionQrPayload(): String? = sessionCode?.let { SessionCodes.toQrPayload(it) }

    // ---- host role ----
    fun becomeHost() { ServiceLocator.signaling.hostOpen() }
    fun cancelHost() { sessionCode = null; goHome() }

    // ---- controller role ----
    fun connectWithCode(code: String) {
        if (!SessionCodes.isValid(code)) { joinError = "invalid-code"; return }
        joinError = null
        lastCode = code
        connecting = true
        ServiceLocator.signaling.join(code)
        startJoinTimeout()
    }

    /**
     * Handle a scanned QR. Two payload kinds are accepted:
     *   - a 6-digit session code (`remot://join?code=…` or bare digits) → dial it.
     *   - a PairingOffer JSON (host's pairing QR) → complete the ECDH pairing
     *     exchange; the host verifies our proof and acks, which surfaces the
     *     safety-number confirmation screen.
     * Returns false when the raw text matched neither.
     */
    fun onScanned(raw: String): Boolean {
        val code = SessionCodes.parseScanned(raw)
        if (code != null) {
            screen = Screen.JOIN
            connectWithCode(code)
            return true
        }
        return try {
            val offer = com.robrion.remot.trust.PairingManager.PairingOffer.parse(raw)
            ServiceLocator.pairing.completeFromScan(offer)
            pairingQr = null
            screen = Screen.PAIRED  // host ack -> safety number screen appears
            true
        } catch (e: Exception) {
            joinError = "unreadable-qr"
            screen = Screen.JOIN
            false
        }
    }

    fun connectToPaired(hostId: String) {
        connecting = true
        ServiceLocator.signaling.joinPaired(hostId)
        startJoinTimeout()
    }

    fun cancelConnecting() {
        cancelJoinTimeout()
        connecting = false
        screen = Screen.JOIN
    }

    /** Retry after a closed/failed session by re-dialing the last code. */
    fun retryConnect() {
        val code = lastCode
        if (code != null) { screen = Screen.JOIN; connectWithCode(code) } else goJoin()
    }

    private fun startJoinTimeout() {
        cancelJoinTimeout()
        joinTimeoutJob = viewModelScope.launch {
            delay(JOIN_TIMEOUT_MS)
            if (connecting) {
                connecting = false
                joinError = "timeout"
                screen = Screen.JOIN
            }
        }
    }
    private fun cancelJoinTimeout() { joinTimeoutJob?.cancel(); joinTimeoutJob = null }

    // ---- consent (host side) ----
    /** Called after the host taps Allow AND the MediaProjection dialog returns OK. */
    fun approveIncoming(resultCode: Int, data: android.content.Intent) {
        val controllerId = pendingControllerId ?: return
        ServiceLocator.signaling.consent(controllerId, true)
        com.robrion.remot.host.ScreenCaptureService.start(
            getApplication(), resultCode, data, controllerId, pendingGrantId
        )
        screen = Screen.SESSION
        clearPending()
    }
    fun declineIncoming() {
        pendingControllerId?.let { ServiceLocator.signaling.consent(it, false) }
        clearPending()
    }
    private fun clearPending() { pendingControllerId = null; pendingUnattended = false; pendingGrantId = null }

    // ---- pairing confirmation ----
    fun confirmPairing() {
        safetyPeerPub?.let { ServiceLocator.pairing.confirm(it) }
        pairingQr = null
        safetyNumber = null; safetyPeerPub = null; goPaired()
    }
    fun rejectPairing() { pairingQr = null; safetyNumber = null; safetyPeerPub = null; goHome() }

    fun revokePeer(peer: PeerIdentity) {
        ServiceLocator.trust.remove(peer.peerPubKeyB64)
        ServiceLocator.grants.revokeAllFor(peer.peerId)
        ServiceLocator.session.endIfPeer(peer.peerId)
        ServiceLocator.signaling.send(JSONObject().apply {
            put("type", "revoke-pairing")
            put("myPub", ServiceLocator.deviceId)
            put("peerPub", peer.peerId)
        })
    }

    // ---- in-app update flow ----
    /** Auto check on foreground; throttled and skipped once dismissed this launch. */
    fun checkForUpdateIfStale() {
        if (updateDismissed || updateState != null) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckMs < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheckMs = now
        checkForUpdate()
    }

    private fun checkForUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val latest = runCatching { UpdateChecker(updateHttp).fetchLatest() }.getOrNull() ?: return@launch
            if (latest.versionCode > BuildConfig.VERSION_CODE) {
                if (!updateDismissed && updateState == null) {
                    updateState = UpdateInfoState.Available(latest)
                }
            }
        }
    }

    /** Download the release APK, then hand it to the system installer. */
    fun downloadAndInstallUpdate() {
        val release = (updateState as? UpdateInfoState.Available)?.release ?: return
        updateState = UpdateInfoState.Downloading(release, 0)
        viewModelScope.launch(Dispatchers.IO) {
            val app: Application = getApplication()
            try {
                val file = ApkInstaller.targetFile(app, release.apkName)
                ApkInstaller.download(app, updateHttp, release.apkUrl, file) { pct ->
                    updateState = UpdateInfoState.Downloading(release, pct)
                }
                val launched = ApkInstaller.launchInstall(app, file)
                updateState = if (launched) UpdateInfoState.InstallStarted(release)
                else UpdateInfoState.Error(release, "Installing from Remot needs permission. Allow it in the next screen, then try again.")
            } catch (e: Exception) {
                updateState = UpdateInfoState.Error(release, e.message?.let { "Update failed: $it" } ?: "Update failed")
            }
        }
    }

    /** User dismissed the dialog; don't nag again this launch. */
    fun dismissUpdate() {
        updateDismissed = true
        updateState = null
    }

    // ---- controller sends input ----
    fun sendTap(nx: Float, ny: Float) {
        ServiceLocator.session.sendControl(JSONObject().put("t", "tap").put("x", nx).put("y", ny))
    }
    fun sendLongPress(nx: Float, ny: Float) {
        ServiceLocator.session.sendControl(JSONObject().put("t", "long-press").put("x", nx).put("y", ny))
    }
    fun sendText(text: String) {
        if (text.isEmpty()) return
        ServiceLocator.session.sendControl(JSONObject().put("t", "text").put("s", text))
    }
    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        ServiceLocator.session.sendControl(
            JSONObject().put("t", "swipe").put("x1", x1).put("y1", y1)
                .put("x2", x2).put("y2", y2).put("ms", ms)
        )
    }
    fun sendKey(key: String) { ServiceLocator.session.sendControl(JSONObject().put("t", "key").put("k", key)) }

    fun endSession() { ServiceLocator.session.endNow(); goHome() }
}
