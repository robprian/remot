'use strict';

// Centralized configuration, sourced from environment with sane local defaults.
module.exports = {
  port: parseInt(process.env.PORT || '8080', 10),

  // TURN (coturn) — must match infra/turnserver.conf
  turn: {
    host: process.env.TURN_HOST || 'localhost',
    secret: process.env.TURN_SECRET || 'dev-only-change-me',
    ttlSec: parseInt(process.env.TURN_TTL_SEC || '3600', 10),
    stunPort: parseInt(process.env.TURN_STUN_PORT || '3478', 10),
    tlsPort: parseInt(process.env.TURN_TLS_PORT || '5349', 10),
  },

  // Session/code lifetimes
  sessionCodeTtlMs: parseInt(process.env.SESSION_CODE_TTL_MS || '300000', 10), // 5 min
  pendingWakeTtlMs: parseInt(process.env.PENDING_WAKE_TTL_MS || '60000', 10),  // 1 min

  // Abuse protection
  rateLimit: {
    // Max inbound messages per connection per window, before the socket is dropped.
    windowMs: parseInt(process.env.RATE_WINDOW_MS || '10000', 10),
    maxMessages: parseInt(process.env.RATE_MAX_MESSAGES || '200', 10),
    // Max join attempts per IP per minute (brute-force / code-guessing protection).
    maxJoinPerMin: parseInt(process.env.RATE_MAX_JOIN_PER_MIN || '10', 10),
  },

  // FCM: only used if firebase-admin is installed AND credentials are present.
  fcmEnabled: process.env.FCM_ENABLED === 'true',
};
