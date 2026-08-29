package com.robrion.remot.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.robrion.remot.host.RemotNotificationListener
import com.robrion.remot.host.RemoteInputService

/**
 * Human-readable lifecycle states for a system-bound service. The distinction
 * matters: "installed" and "enabled in Settings" and "actually connected by the
 * system" are three different things, and the UI must not conflate them.
 */
enum class ServiceState { NOT_INSTALLED, INSTALLED, ENABLED, CONNECTED }

/**
 * Queries the REAL Android system state for Remot's two system-bound services
 * (AccessibilityService and NotificationListenerService). Nothing here relies on
 * a locally cached boolean — every call reads the actual Settings / system
 * service state so the UI reflects what the OS really knows.
 */
object ServiceStatus {

    // ---- Accessibility ----

    fun accessibilityState(context: Context): ServiceState {
        val installed = componentEnabled(
            context, ComponentName(context, "com.robrion.remot.host.RemoteInputService")
        ) != null
        val enabled = isAccessibilityServiceEnabled(context)
        val connected = RemoteInputService.isConnected
        return resolveServiceState(installed, enabled, connected)
    }

    /**
     * Authoritative check: is Remot's accessibility service in the enabled list?
     * Reads Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES (the same source the
     * OS Settings UI reads), never a cached boolean.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context, "com.robrion.remot.host.RemoteInputService"
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // Compare both the flattened form and the plain component name; some
        // OEMs store one or the other.
        return isComponentListed(
            enabledServices, expected, "com.robrion.remot/.host.RemoteInputService"
        )
    }

    /**
     * True when the AccessibilityService is actually bound by the system.
     * The service sets its companion instance only inside onServiceConnected()
     * and clears it in onDestroy(), so this reflects the real system bind state.
     */
    fun isAccessibilityServiceConnected(context: Context): Boolean =
        RemoteInputService.isConnected

    // ---- Notification Listener ----

    fun notificationListenerState(context: Context): ServiceState {
        val installed = componentEnabled(
            context, ComponentName(context, "com.robrion.remot.host.RemotNotificationListener")
        ) != null
        val enabled = isNotificationListenerEnabled(context)
        val connected = RemotNotificationListener.isConnected
        return resolveServiceState(installed, enabled, connected)
    }

    /**
     * Authoritative check for Notification Access: parses
     * Settings.Secure.ENABLED_NOTIFICATION_LISTENERS exactly as the system does.
     * Does NOT treat "service exists" as "access granted".
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context, "com.robrion.remot.host.RemotNotificationListener"
        ).flattenToString()
        // "enabled_notification_listeners" is the Settings.Secure key Android's
        // own Settings UI reads; the framework constant is not in the public SDK.
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return isComponentListed(flat, expected)
    }

    // ---- Helpers ----

    /**
     * Pure membership predicate over a Settings.Secure ':'-delimited component
     * list (e.g. ENABLED_ACCESSIBILITY_SERVICES). Pure JVM — no Android
     * dependency — so it is fully unit-testable. Trims whitespace and compares
     * case-insensitively to tolerate OEM formatting; every match must be a whole
     * component name, never a bare substring.
     */
    internal fun isComponentListed(flattenedList: String?, vararg expected: String): Boolean {
        if (flattenedList.isNullOrEmpty()) return false
        val wanted = Array(expected.size) { expected[it].trim() }
        for (entry in flattenedList.split(':')) {
            val name = entry.trim()
            if (name.isEmpty()) continue
            for (w in wanted) {
                if (name.equals(w, ignoreCase = true)) return true
            }
        }
        return false
    }

    /**
     * Pure state resolver. The precedence — installed → enabled in Settings →
     * actually connected by the system — encodes the fix for "installed is not
     * enabled" and "enabled in Settings is not yet connected". Pure JVM.
     */
    internal fun resolveServiceState(installed: Boolean, enabled: Boolean, connected: Boolean): ServiceState =
        when {
            !installed -> ServiceState.NOT_INSTALLED
            !enabled -> ServiceState.INSTALLED
            !connected -> ServiceState.ENABLED
            else -> ServiceState.CONNECTED
        }

    private fun componentEnabled(context: Context, component: ComponentName): ComponentName? {
        return try {
            context.packageManager.getServiceInfo(component, 0)
            component
        } catch (e: Exception) {
            null
        }
    }

    // ---- Intent helpers (never crash, always fall back safely) ----

    /** Opens the right Android settings screen; safe on all supported versions. */
    fun openAccessibilitySettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openSettings(context)
        }
    }

    /** Opens Notification Access settings, with a safe fallback. */
    fun openNotificationListenerSettings(context: Context) {
        try {
            val component = ComponentName(
                context, "com.robrion.remot.host.RemotNotificationListener"
            )
            // ACTION_NOTIFICATION_LISTENER_SETTINGS opens the screen filtered to
            // the requesting app on most builds; the generic one is the fallback.
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            openSettings(context)
        }
    }

    private fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Nothing else we can do; never crash.
        }
    }
}
