package com.robrion.remot.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure logic in [ServiceStatus] — the component-list
 * membership predicate used against Settings.Secure and the installed →
 * enabled → connected state resolver. Both are JVM-pure by design so the
 * "installed ≠ enabled ≠ connected" behavior can be pinned down here without
 * an Android Context / Robolectric.
 */
class ServiceStatusTest {

    // ---- isComponentListed ----

    @Test fun nullOrEmptyListIsNotEnabled() {
        assertFalse(ServiceStatus.isComponentListed(null, "a"))
        assertFalse(ServiceStatus.isComponentListed("", "a"))
        assertFalse(ServiceStatus.isComponentListed("   ", "a"))
    }

    @Test fun singleExactMatch() {
        val expected = "com.robrion.remot/com.robrion.remot.host.RemoteInputService"
        assertTrue(ServiceStatus.isComponentListed(expected, expected))
    }

    @Test fun matchAmongMultipleEntries() {
        val list =
            "com.a/.X:com.robrion.remot/com.robrion.remot.host.RemoteInputService:com.b/.Y"
        assertTrue(
            ServiceStatus.isComponentListed(
                list, "com.robrion.remot/com.robrion.remot.host.RemoteInputService"
            )
        )
    }

    @Test fun secondExpectedVariantMatches() {
        // accessibility also accepts the short "package/.Service" form some OEMs store.
        val list = "com.robrion.remot/.host.RemoteInputService"
        assertTrue(ServiceStatus.isComponentListed(
            list, "com.robrion.remot/com.robrion.remot.host.RemoteInputService",
            "com.robrion.remot/.host.RemoteInputService"
        ))
    }

    @Test fun caseInsensitiveOemFormatting() {
        assertTrue(
            ServiceStatus.isComponentListed(
                "COM.ROBRION.REMOT/com.robrion.remot.host.RemoteInputService",
                "com.robrion.remot/com.robrion.remot.host.RemoteInputService"
            )
        )
    }

    @Test fun whitespaceAroundEntriesIsTolerated() {
        assertTrue(
            ServiceStatus.isComponentListed(
                " com.a/.X : com.robrion.remot/com.robrion.remot.host.RemoteInputService ",
                "com.robrion.remot/com.robrion.remot.host.RemoteInputService"
            )
        )
    }

    @Test fun noMatchReturnsFalse() {
        assertFalse(
            ServiceStatus.isComponentListed(
                "com.a/.X:com.b/.Y", "com.robrion.remot/com.robrion.remot.host.RemoteInputService"
            )
        )
    }

    @Test fun wholeComponentOnlyNeverSubstring() {
        // A service that merely shares a prefix must NOT match.
        val list = "com.robrion.remot/com.robrion.remot.host.RemoteInputServiceEvil"
        assertFalse(
            ServiceStatus.isComponentListed(list, "com.robrion.remot/com.robrion.remot.host.RemoteInputService")
        )
    }

    @Test fun trailingColonAndEmptyEntriesAreIgnored() {
        val list = "com.a/.X::com.b/.Y:"
        assertTrue(ServiceStatus.isComponentListed(list, "com.b/.Y"))
        assertFalse(ServiceStatus.isComponentListed(list, ""))
    }

    // ---- resolveServiceState ----

    @Test fun notInstalledWins() {
        assertEquals(
            ServiceState.NOT_INSTALLED,
            ServiceStatus.resolveServiceState(installed = false, enabled = true, connected = true)
        )
    }

    @Test fun installedButNotEnabled() {
        assertEquals(
            ServiceState.INSTALLED,
            ServiceStatus.resolveServiceState(installed = true, enabled = false, connected = false)
        )
    }

    @Test fun enabledButNotYetConnected() {
        // This is the Android 16 case the fix targets: enabled in Settings but
        // the system has not bound the service yet. Must NOT report "Connected".
        assertEquals(
            ServiceState.ENABLED,
            ServiceStatus.resolveServiceState(installed = true, enabled = true, connected = false)
        )
    }

    @Test fun fullyConnected() {
        assertEquals(
            ServiceState.CONNECTED,
            ServiceStatus.resolveServiceState(installed = true, enabled = true, connected = true)
        )
    }
}