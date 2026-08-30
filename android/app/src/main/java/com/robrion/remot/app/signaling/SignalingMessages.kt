package com.robrion.remot.signaling

import org.json.JSONObject

/**
 * Pure builders for the signaling wire messages, extracted from
 * [SignalingClient] so the protocol contract can be unit-tested without a
 * live socket. The critical rule pinned here: the challenge `nonce` MUST be
 * echoed verbatim (a response without it is rejected by the server with
 * `auth-failed` — the root cause of "Signaling unreachable" on device).
 */
object SignalingMessages {

    /**
     * Builds an `auth-response`. Echoes [nonceB64] verbatim and includes the
     * signature over that nonce. For server-direct registration [to] is blank
     * (the `to` field is omitted); for a peer-relayed per-session auth it
     * carries the target device id.
     */
    fun authResponse(to: String, nonceB64: String, sigB64: String): JSONObject {
        val o = JSONObject()
            .put("type", "auth-response")
            .put("nonce", nonceB64)
            .put("sig", sigB64)
        if (to.isNotBlank()) o.put("to", to)
        return o
    }
}