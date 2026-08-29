package com.robrion.remot.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.robrion.remot.BuildConfig
import com.robrion.remot.ServiceLocator
import com.robrion.remot.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection

/** Lifecycle state of one infrastructure endpoint. */
enum class EndpointState { CHECKING, ONLINE, OFFLINE, UNKNOWN }

/** One immutable snapshot of infrastructure health, shared by all screens. */
data class NetworkHealth(
    val internet: EndpointState = EndpointState.UNKNOWN,
    val signaling: EndpointState = EndpointState.UNKNOWN,
    val signalingUrl: String? = null,
    val signalingError: String? = null,
    val stun: EndpointState = EndpointState.UNKNOWN,
    val turn: EndpointState = EndpointState.UNKNOWN,
    val turnHost: String? = null,
    val latencyMs: Long? = null,
    val lastCheckedAt: Long = 0L,
    val checking: Boolean = false,
    val error: String? = null,
)

/**
 * SINGLE source of truth for infrastructure health. Home, the Services screen
 * and Diagnostics all read the same [health] state — there is exactly ONE
 * polling loop, started when any screen is active and stopped when the app is
 * backgrounded. Latency is a real measured STUN/TURN round-trip, never a guess.
 *
 * Wiring:
 *   ServiceLocator.init() → NetworkHealthRepository.start()
 *   MainViewModel → observes [health] and calls [refreshNow] on resume/manual
 */
class NetworkHealthRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    @Volatile private var pollingJob: Job? = null
    @Volatile private var running = false

    /** Backing state (thread-safe via @Volatile swaps of immutable data). */
    @Volatile var health: NetworkHealth = NetworkHealth()
        private set

    /** Callback for UI updates (set once by the ViewModel). */
    var onHealthChanged: ((NetworkHealth) -> Unit)? = null

    /** Last known STUN/TURN endpoint + credentials for the probe. */
    @Volatile private var iceServers: List<PeerConnection.IceServer> = emptyList()

    // ---- lifecycle ----

    /** Begin periodic checks (15s). Safe to call repeatedly. */
    fun start() {
        if (running) return
        running = true
        refreshNow()
        pollingJob = scope.launch {
            while (running) {
                delay(15_000)
                if (running) refreshNow()
            }
        }
    }

    /** Stop all polling. Idempotent; safe from any lifecycle callback. */
    fun stop() {
        running = false
        pollingJob?.cancel()
        pollingJob = null
    }

    fun isRunning() = running

    /** Immediate check, asynchronous (background dispatcher). */
    fun refreshNow() {
        if (!running && pollingJob == null) running = true
        updateHealth(health.copy(checking = true, error = null))
        scope.launch {
            val result = checkAll()
            updateHealth(result.copy(checking = false))
        }
    }

    /** Called by the signaling listener whenever fresh TURN credentials arrive. */
    fun onIceServers(servers: List<PeerConnection.IceServer>) {
        iceServers = servers
        refreshNow()
    }

    // ---- the actual check (runs on a background thread) ----

    private suspend fun checkAll(): NetworkHealth {
        val now = System.currentTimeMillis()

        val internet = checkInternet()
        val signaling = if (ServiceLocator.signaling.isConnected) EndpointState.ONLINE
        else EndpointState.OFFLINE

        // Developer-facing signaling diagnostic data (URL + why it's failing).
        val signalingUrl = ServiceLocator.signaling.signalingUrl
        val signalingError = if (signaling == EndpointState.ONLINE) null
        else ServiceLocator.signaling.lastConnectError

        // Derive the TURN/STUN endpoint + credentials from the ice servers the
        // signaling server actually issued (never hardcoded, never fake).
        val turnEndpoint = parseTurnEndpoint(iceServers)
        var stun = EndpointState.UNKNOWN
        var turn = EndpointState.UNKNOWN
        var latency: Long? = null
        var error: String? = null

        if (internet == EndpointState.ONLINE && turnEndpoint != null) {
            val result = probeEndpoint(turnEndpoint)

            stun = if (result.stunOk) EndpointState.ONLINE else EndpointState.OFFLINE
            turn = if (result.turnOk) EndpointState.ONLINE else EndpointState.OFFLINE
            latency = result.latencyMs
            error = result.error
        } else if (turnEndpoint == null && internet == EndpointState.ONLINE) {
            stun = EndpointState.UNKNOWN
            turn = EndpointState.UNKNOWN
            error = "no-turn-config"
        }

        return NetworkHealth(
            internet = internet,
            signaling = signaling,
            signalingUrl = signalingUrl,
            signalingError = signalingError,
            stun = stun,
            turn = turn,
            turnHost = turnEndpoint?.host,
            latencyMs = latency,
            lastCheckedAt = now,
            checking = false,
            error = error,
        )
    }

    private fun updateHealth(h: NetworkHealth) {
        health = h
        onHealthChanged?.invoke(h)
    }

    private fun checkInternet(): EndpointState {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return EndpointState.OFFLINE
            val caps = cm.getNetworkCapabilities(network) ?: return EndpointState.OFFLINE
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) EndpointState.ONLINE else EndpointState.OFFLINE
        } catch (e: Exception) {
            EndpointState.UNKNOWN
        }
    }

    /**
     * Probes the issued TURN endpoint. If that host is unreachable and a direct
     * public IP was configured at build time (SERVER_IP secret), retries against
     * the IP so connectivity is honest even when the hostname route is down.
     */
    private fun probeEndpoint(ep: TurnEndpoint): StunTurnResult {
        fun probe(host: String) = runCatching {
            StunTurnProbe(
                host = host,
                port = ep.port,
                username = ep.username,
                password = ep.password,
            ).probe()
        }.getOrElse { StunTurnResult.unknown }

        val primary = probe(ep.host)
        // Only fall back to the IP when the primary genuinely failed its STUN
        // step (DNS or unreachable) — not when it just lacked credentials.
        val ip = BuildConfig.SERVER_IP
        if (ip.isNotBlank() && ip != ep.host && primary.error in setOf("dns", "stun", "timeout")) {
            val fallback = probe(ip)
            return if (fallback.stunOk || fallback.turnOk) fallback else primary
        }
        return primary
    }

    // ---- TURN endpoint parsing ----

    private data class TurnEndpoint(
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
    )

    private fun parseTurnEndpoint(servers: List<PeerConnection.IceServer>): TurnEndpoint? {
        if (servers.isEmpty()) return null
        for (s in servers) {
            val urls = runCatching { s.urls.toList() }.getOrElse { emptyList() }
            for (u in urls) {
                // turn:host:port?transport=udp / stun:host:port
                val m = Regex("^(turn|turns|stun):([^:?]+)(?::(\\d+))?").find(u) ?: continue
                val scheme = m.groupValues[1]
                val host = m.groupValues[2]
                val port = m.groupValues[3].toIntOrNull() ?: 3478
                val username = runCatching { s.username }.getOrNull()
                val password = runCatching { s.password }.getOrNull()
                return TurnEndpoint(host, port, username, password)
            }
        }
        return null
    }

    // ---- network change observer: re-check immediately ----

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNow()
        override fun onLost(network: Network) = refreshNow()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshNow()
    }

    /** Register the connectivity observer (call once in init). */
    fun observeNetworkChanges() {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        }
    }

    fun unobserveNetworkChanges() {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 15_000L
    }
}
