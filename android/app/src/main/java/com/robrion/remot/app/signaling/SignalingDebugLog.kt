package com.robrion.remot.signaling

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bounded, in-memory log of the signaling WebSocket connection lifecycle.
 *
 * Signaling uses OkHttp WebSocket, which does NOT pass through OkHttp
 * interceptors — so an HTTP inspector like Chucker cannot show the WS
 * handshake or its failures (Chucker issue #675). This is the on-device
 * record of every connect attempt, the endpoint tried, and the exact
 * OS-level error, surfaced in Diagnostics with a copy button so the user can
 * paste the failure back to the developer.
 *
 * Thread-safe; a plain singleton (no Compose state) — the Diagnostics screen
 * re-reads [snapshot] on recomposition (driven by the 15s health poll).
 */
object SignalingDebugLog {

    data class Entry(
        val time: Long,
        val endpoint: String,
        val event: String,
        val detail: String,
    )

    private const val TAG = "RemotSignaling"
    private const val MAX = 120
    private val entries = CopyOnWriteArrayList<Entry>()

    /** Most recent failing endpoint + reason, for a quick at-a-glance display. */
    @Volatile
    var lastError: String? = null
        private set

    /** Appends an event (CONNECTING / CONNECTED / FAILED / CLOSED / REGISTER-*). */
    fun record(endpoint: String, event: String, detail: String = "") {
        val e = Entry(System.currentTimeMillis(), endpoint, event, detail)
        entries.add(e)
        while (entries.size > MAX) entries.removeAt(0)
        if (event == "FAILED") {
            lastError = "$endpoint → $detail"
            Log.w(TAG, "$event $endpoint $detail")
        } else {
            Log.i(TAG, "$event $endpoint $detail")
        }
    }

    /** Newest-first copy for display. */
    fun snapshot(): List<Entry> = entries.toList().asReversed()

    /** Plain-text dump for the "copy" action / bug reports. */
    fun dump(): String {
        if (entries.isEmpty()) return "(no signaling activity recorded yet)"
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return entries.toList()
            .joinToString("\n") { e ->
                "${fmt.format(java.util.Date(e.time))} [${e.event}] ${e.endpoint}${if (e.detail.isBlank()) "" else " — ${e.detail}"}"
            }
    }

    fun clear() = entries.clear()
}