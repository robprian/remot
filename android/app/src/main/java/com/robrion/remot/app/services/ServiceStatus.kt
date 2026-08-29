package com.robrion.remot.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.robrion.remot.host.RemotNotificationListener

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
        // 1) Installed? (component declared in the merged manifest)
        val installed = componentEnabled(
            context, ComponentName(context, "com.robrion.remot.host.RemoteInputService")
        ) != null
        if (!installed) return ServiceState.NOT_INSTALLED

        // 2) Enabled in Settings? (authoritative — read the secure setting)
        val enabled = isAccessibilityServiceEnabled(context)
        if (!enabled) return ServiceState.INSTALLED

        // 3) Actually connected by the system? (the service object is alive)
        return if (isAccessibilityServiceConnected(context)) ServiceState.CONNECTED
        else ServiceState.ENABLED
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
        val enabledList = TextUtils.SimpleStringSplitter(':').apply {
            enabledServices.let { setString(it) }
        }
        for (name in enabledList) {
            // Compare both the flattened form and the plain component name;
            // some OEMs store one or the other.
            val normalized = name.trim()
            if (normalized.equals(expected, ignoreCase = true) ||
                normalized.equals(
                    "com.robrion.remot/.host.RemoteInputService", ignoreCase = true
                )
            ) {
                return true
            }
        }
        return false
    }

    /**
     * True when the AccessibilityService is actually bound by the system.
     * The service sets its companion instance only inside onServiceConnected()
     * and clears it in onDestroy(), so this reflects the real system bind state.
     */
    fun isAccessibilityServiceConnected(context: Context): Boolean =
        com.robrion.remot.host.RemoteInputService.isConnected

    // ---- Notification Listener ----

    fun notificationListenerState(context: Context): ServiceState {
        // 1) Installed?
        val installed = componentEnabled(
            context, ComponentName(context, "com.robrion.remot.host.RemotNotificationListener")
        ) != null
        if (!installed) return ServiceState.NOT_INSTALLED

        // 2) Access granted in Settings?
        if (!isNotificationListenerEnabled(context)) return ServiceState.INSTALLED

        // 3) Actually connected?
        return if (RemotNotificationListener.isConnected) ServiceState.CONNECTED
        else ServiceState.ENABLED
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
        val split = TextUtils.SimpleStringSplitter(':').apply { setString(flat) }
        for (name in split) {
            if (name.trim().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    // ---- Helpers ----

    /** Resolve the component as declared in the merged manifest; null = not declared. */
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
