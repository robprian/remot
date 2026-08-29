'use strict';

// Minimal structured logging: one JSON object per line.
// Never include secrets (TURN_SECRET, FCM credentials, tokens, pairing keys).

function emit(level, msg, fields) {
  const line = { ts: new Date().toISOString(), level, msg, ...(fields || {}) };
  const out = JSON.stringify(line);
  if (level === 'error' || level === 'warn') console.error(out);
  else console.log(out);
}

module.exports = {
  info: (msg, fields) => emit('info', msg, fields),
  warn: (msg, fields) => emit('warn', msg, fields),
  error: (msg, fields) => emit('error', msg, fields),
};
