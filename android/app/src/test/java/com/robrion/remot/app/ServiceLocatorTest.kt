package com.robrion.remot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [signalingUrlCandidates] — the ordered signaling endpoint
 * list. The critical regression pinned here: a `wss://` form must NEVER be
 * emitted for a port that only serves plain `ws://` (e.g. :8080), because the
 * server answers "Unable to parse TLS packet header". TLS :8443 must always
 * come first for every host so production prefers the secure channel.
 */
class ServiceLocatorTest {

    @Test
    fun `plain primary never yields wss on the plain port`() {
        val urls = signalingUrlCandidates(
            primary = "ws://turn.robrion.net:8080",
            serverIp = "203.0.113.10",
            serverUrlAlt = "",
            serverIpAlt = "",
        )
        // TLS on the TLS port first, then the plain form — never wss://:8080.
        assertEquals(listOf(
            "wss://turn.robrion.net:8443",
            "ws://turn.robrion.net:8080",
            "wss://203.0.113.10:8443",
            "ws://203.0.113.10:8080",
        ), urls)
        assertFalse("no wss:// on the plain port", urls.any { it.contains("wss://") && it.endsWith(":8080") })
    }

    @Test
    fun `domain primary with explicit wss port keeps its own TLS port`() {
        val urls = signalingUrlCandidates(
            primary = "wss://turn.robrion.net:8443",
            serverIp = "203.0.113.10",
            serverUrlAlt = "",
            serverIpAlt = "",
        )
        assertEquals("wss://turn.robrion.net:8443", urls.first())
        assertTrue(urls.contains("wss://turn.robrion.net:8443"))
        assertTrue(urls.contains("ws://turn.robrion.net:8080"))
        assertTrue(urls.contains("wss://203.0.113.10:8443"))
        assertFalse(urls.any { it.contains("wss://") && it.endsWith(":8080") })
    }

    @Test
    fun `wss on port 443 is retained for the configured host`() {
        val urls = signalingUrlCandidates(
            primary = "wss://signaling.example.com:443",
            serverIp = "",
            serverUrlAlt = "",
            serverIpAlt = "",
        )
        assertTrue(urls.contains("wss://signaling.example.com:443"))
        assertTrue(urls.contains("wss://signaling.example.com:8443"))
        assertTrue(urls.contains("ws://signaling.example.com:8080"))
    }

    @Test
    fun `alternate host and ip are appended after the primary`() {
        val urls = signalingUrlCandidates(
            primary = "wss://turn.robrion.net:8443",
            serverIp = "203.0.113.10",
            serverUrlAlt = "ws://backup.example.com:8080",
            serverIpAlt = "203.0.113.20",
        )
        // Primary host first, then its direct IP, then the alt host + alt IP.
        val primaryIdx = urls.indexOf("wss://turn.robrion.net:8443")
        val altHostIdx = urls.indexOf("wss://backup.example.com:8443")
        val altIpIdx = urls.indexOf("wss://203.0.113.20:8443")
        assertTrue(primaryIdx in 0..1)
        assertTrue(altHostIdx > primaryIdx)
        assertTrue(altIpIdx > altHostIdx)
        // The backup URL is plain ws — never derive a wss:// on its :8080.
        assertFalse(urls.any { it.contains("wss://backup.example.com") && it.endsWith(":8080") })
    }

    @Test
    fun `list is de-duplicated`() {
        val urls = signalingUrlCandidates(
            primary = "ws://turn.robrion.net:8080",
            serverIp = "turn.robrion.net",
            serverUrlAlt = "",
            serverIpAlt = "turn.robrion.net",
        )
        assertEquals(urls.size, urls.distinct().size)
    }
}
