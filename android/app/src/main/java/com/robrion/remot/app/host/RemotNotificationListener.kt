package com.robrion.remot.host

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification listener for Remot. Declared with the
 * BIND_NOTIFICATION_LISTENER_SERVICE permission and exported=true so Android's
 * Settings can bind it. The listener itself is intentionally minimal: its only
 * job is to prove Notification Access is granted and to track the real
 * system connection state (installed vs. access granted vs. actually connected).
 *
 * Notification content is NEVER logged, transmitted, or persisted — the service
 * does not read [StatusBarNotification] extras or text.
 */
class RemotNotificationListener : NotificationListenerService() {

    @Volatile private var systemConnected = false

    override fun onListenerConnected() {
        systemConnected = true
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        systemConnected = false
        if (instance === this) instance = null
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        systemConnected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Existence of this method is required by the base class contract; we do not
     * read or expose the notification payload.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        private const val TAG = "RemotNotifListener"

        @Volatile var instance: RemotNotificationListener? = null
            private set

        /** True only after the system has actually connected the listener. */
        val isConnected: Boolean get() = instance != null
    }
}
