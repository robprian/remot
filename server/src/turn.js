'use strict';

const crypto = require('crypto');
const cfg = require('./config');

// Generate short-lived TURN credentials compatible with coturn's
// `use-auth-secret` / `static-auth-secret` mechanism.
// username = "<unixExpiry>:<label>", password = base64(HMAC-SHA1(secret, username)).
function turnCredentials(label = 'remot') {
  const expiry = Math.floor(Date.now() / 1000) + cfg.turn.ttlSec;
  const username = `${expiry}:${label}`;
  const password = crypto
    .createHmac('sha1', cfg.turn.secret)
    .update(username)
    .digest('base64');
  return { username, password, ttl: cfg.turn.ttlSec };
}

// The iceServers array the client feeds into PeerConnection.RTCConfiguration.
function iceServers() {
  const { username, password } = turnCredentials();
  const h = cfg.turn.host;
  return [
    { urls: `stun:${h}:${cfg.turn.stunPort}` },
    { urls: `turn:${h}:${cfg.turn.stunPort}?transport=udp`, username, credential: password },
    // TURN-over-TCP fallback: many mobile/carrier networks block UDP to
    // arbitrary ports but allow TCP. coturn listens on the STUN/TURN port for
    // TCP too (WebRTC ICE can fall back to a TURN TCP relay), so advertise it.
    { urls: `turn:${h}:${cfg.turn.stunPort}?transport=tcp`, username, credential: password },
    // NOTE: `turns:` (TLS on 5349) is deliberately NOT advertised — coturn has
    // no TLS certificate configured, so a turns URL can never connect. Advertise
    // it again only after provisioning a cert and enabling tls-listening-port
    // on the server. ICE falls back to the UDP relay above.
  ];
}

module.exports = { turnCredentials, iceServers };
