'use strict';

const cfg = require('./config');
const log = require('./log');
const { fcmTokens } = require('./state');

// firebase-admin is an optional dependency. If it's absent or FCM is disabled,
// wakeDevice() becomes a logged no-op so the rest of the system still runs
// (attended sessions work fine without wake).
let messaging = null;
if (cfg.fcmEnabled) {
  try {
    const admin = require('firebase-admin');
    admin.initializeApp({ credential: admin.credential.applicationDefault() });
    messaging = admin.messaging();
    log.info('fcm_enabled');
  } catch (e) {
    log.warn('fcm_init_failed', { error: e.message });
  }
} else {
  log.info('fcm_disabled');
}

/**
 * Wake a dozing/backgrounded host via a high-priority, data-only push so the
 * device's onMessageReceived runs and it reconnects its signaling socket.
 */
async function wakeDevice(deviceId, payload) {
  const token = fcmTokens.get(deviceId);
  if (!token) {
    log.warn('fcm_no_token', { deviceId });
    return { ok: false, reason: 'no-token' };
  }
  if (!messaging) {
    log.info('fcm_noop_wake', { deviceId });
    return { ok: false, reason: 'fcm-disabled' };
  }

  const message = {
    token,
    // DATA-ONLY (no `notification` block) so client code always runs in background.
    data: {
      type: 'wake',
      controllerId: String(payload.controllerId ?? ''),
      sessionId: String(payload.sessionId ?? ''),
      unattended: String(payload.unattended ?? ''),
    },
    android: {
      priority: 'high', // required to bypass Doze deferral
      ttl: cfg.pendingWakeTtlMs, // stale wakes should expire, not power on later
    },
  };

  try {
    const id = await messaging.send(message);
    return { ok: true, id };
  } catch (e) {
    if (e.code === 'messaging/registration-token-not-registered') {
      fcmTokens.delete(deviceId); // dead token; device must re-report
    }
    log.error('fcm_send_failed', { deviceId, error: e.message });
    return { ok: false, reason: e.code || 'send-failed' };
  }
}

module.exports = { wakeDevice };
