'use strict';

const crypto = require('crypto');
const http = require('http');
const { WebSocketServer } = require('ws');
const cfg = require('./config');
const log = require('./log');
const S = require('./state');
const { wakeDevice } = require('./fcm');
const { iceServers } = require('./turn');

function send(ws, obj) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
}
function code6() {
  return String(crypto.randomInt(100000, 1000000));
}

/** Send a register-failed notice and close the connection (a rejected registration is dead). */
function rejectRegister(ws, reason) {
  send(ws, { type: 'register-failed', reason });
  ws.close(4000, reason);
}

/** Record a join attempt from an IP; true when the client is rate-limited. */
function joinRateLimited(ip) {
  const now = Date.now();
  const windowStart = now - 60_000;
  const prev = S.joinAttempts.get(ip);
  if (prev) {
    const recent = prev.filter((t) => t >= windowStart);
    if (recent.length >= cfg.rateLimit.maxJoinPerMin) {
      S.joinAttempts.set(ip, recent);
      return true;
    }
    recent.push(now);
    S.joinAttempts.set(ip, recent);
  } else {
    S.joinAttempts.set(ip, [now]);
  }
  return false;
}

function start() {
  // HTTP server hosts the WebSocket upgrade AND a small health endpoint.
  const server = http.createServer((req, res) => {
    if (req.method === 'GET' && (req.url === '/healthz' || req.url === '/health')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'ok', uptimeSec: Math.round(process.uptime()), devices: S.devices.size }));
      return;
    }
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found' }));
  });

  const wss = new WebSocketServer({ server });

  wss.on('connection', (ws, req) => {
    ws.deviceId = null;
    ws.ip = req.socket.remoteAddress;
    ws.isAlive = true;
    ws.msgWindow = { count: 0, resetAt: Date.now() + cfg.rateLimit.windowMs };
    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (raw) => {
      // Per-connection flood protection: drop sockets that exceed the window.
      const now = Date.now();
      if (ws.msgWindow.resetAt < now) {
        ws.msgWindow = { count: 0, resetAt: now + cfg.rateLimit.windowMs };
      }
      if (++ws.msgWindow.count > cfg.rateLimit.maxMessages) {
        log.warn('rate_limit_connection', { ip: ws.ip });
        ws.terminate();
        return;
      }

      let m;
      try { m = JSON.parse(raw.toString()); } catch { return; }
      handle(ws, m).catch((e) => log.error('handler_error', { type: m?.type, error: e.message }));
    });

    ws.on('close', () => {
      if (ws.deviceId) S.devices.delete(ws.deviceId);
    });
  });

  // Liveness ping — drop half-open sockets.
  const ping = setInterval(() => {
    wss.clients.forEach((ws) => {
      if (!ws.isAlive) return ws.terminate();
      ws.isAlive = false;
      ws.ping();
    });
  }, 30000);
  wss.on('close', () => clearInterval(ping));

  // Prune expired codes, pending wakes, and rate-limit windows.
  const prune = setInterval(() => {
    const now = Date.now();
    for (const [c, s] of S.sessions) if (s.expires < now) S.sessions.delete(c);
    for (const [h, w] of S.pendingWakes) if (w.expires < now) S.pendingWakes.delete(h);
    for (const [ip, t] of S.joinAttempts) if (t[t.length - 1] < now - 60_000) S.joinAttempts.delete(ip);
  }, 15000);

  server.listen(cfg.port, () => log.info('signaling_listening', { port: cfg.port }));

  // Graceful shutdown: stop timers, close sockets with 1001, exit.
  const shutdown = (signal) => {
    log.info('shutdown', { signal });
    clearInterval(ping);
    clearInterval(prune);
    wss.clients.forEach((ws) => ws.close(1001, 'server shutdown'));
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  return server;
}

async function handle(ws, m) {
  switch (m.type) {
    // ---- identity / registration (AUTHENTICATED) ----
    //
    // Registration proves ownership of the claimed deviceId: the client must
    // present the DER public key whose SHA-256 IS the deviceId, then sign a
    // fresh server nonce with the matching private key. Until that completes,
    // the socket cannot claim a deviceId or receive routed messages, so an
    // attacker cannot hijack a victim's id or message routing.
    case 'register': {
      if (ws.deviceId) return rejectRegister(ws, 'already-registered');
      if (ws.pendingAuth) return rejectRegister(ws, 'already-registering');

      const { deviceId, pubKeyB64 } = m;
      if (typeof deviceId !== 'string' || deviceId.length < 16) {
        return rejectRegister(ws, 'bad-id');
      }
      let pubDer;
      try { pubDer = Buffer.from(pubKeyB64, 'base64'); } catch { pubDer = null; }
      if (!pubDer || pubDer.length < 32 || pubDer.length > 2048) {
        return rejectRegister(ws, 'bad-key');
      }
      const idOfKey = crypto.createHash('sha256').update(pubDer).digest('hex');
      if (idOfKey !== deviceId) return rejectRegister(ws, 'bad-key');

      const nonce = crypto.randomBytes(32);
      ws.pendingAuth = { deviceId, pubDer, nonce };
      send(ws, { type: 'auth-challenge', nonce: nonce.toString('base64') });
      break;
    }

    case 'auth-response': {
      // Without a `to` target this completes the register handshake (direct
      // server<->client); with a `to` it is a peer-relayed per-session auth.
      if (m.to) {
        if (!ws.deviceId) return send(ws, { type: 'error', reason: 'not-registered' });
        send(S.devices.get(m.to), { ...m, from: ws.deviceId });
        break;
      }

      const pa = ws.pendingAuth;
      if (!pa) return rejectRegister(ws, 'no-challenge');
      if (m.nonce !== pa.nonce.toString('base64')) {
        return rejectRegister(ws, 'auth-failed');
      }
      let sig;
      try { sig = Buffer.from(m.sig, 'base64'); } catch { sig = null; }
      const verifier = crypto.createVerify('SHA256');
      verifier.update(pa.nonce);
      const valid = sig && verifier.verify(
        { key: pa.pubDer, format: 'der', type: 'spki', dsaEncoding: 'der' },
        sig
      );
      if (!valid) return rejectRegister(ws, 'auth-failed');

      ws.pendingAuth = null;
      ws.deviceId = pa.deviceId;
      S.devices.set(pa.deviceId, ws);
      log.info('device_registered', { deviceId: pa.deviceId, ip: ws.ip });
      send(ws, { type: 'registered', deviceId: pa.deviceId, iceServers: iceServers() });

      // Flush any wake that was queued while the host was offline.
      const pending = S.pendingWakes.get(pa.deviceId);
      if (pending && pending.expires > Date.now()) {
        send(ws, {
          type: 'join-request',
          controllerId: pending.controllerId,
          unattended: pending.unattended,
        });
        S.pendingWakes.delete(pa.deviceId);
      }
      break;
    }

    case 'report-token': {
      if (ws.deviceId) S.fcmTokens.set(ws.deviceId, m.fcmToken);
      break;
    }

    case 'turn-credentials': {
      send(ws, { type: 'turn-credentials', iceServers: iceServers() });
      break;
    }

    // ---- host opens a code-based session ----
    case 'host-open': {
      if (!ws.deviceId) return send(ws, { type: 'error', reason: 'not-registered' });
      const code = code6();
      S.sessions.set(code, { hostId: ws.deviceId, expires: Date.now() + cfg.sessionCodeTtlMs });
      send(ws, { type: 'session-code', code });
      break;
    }

    // ---- controller dials in (by code, or directly to a paired host) ----
    case 'join': {
      if (!ws.deviceId) return send(ws, { type: 'join-failed', reason: 'not-registered' });
      if (joinRateLimited(ws.ip)) return send(ws, { type: 'join-failed', reason: 'rate-limited' });

      let hostId = null;
      if (m.code) {
        const s = S.sessions.get(m.code);
        if (!s || s.expires < Date.now()) return send(ws, { type: 'join-failed', reason: 'invalid-code' });
        hostId = s.hostId;
        S.sessions.delete(m.code); // one-time use
      } else if (m.hostId) {
        // paired-direct dial: requires an existing pairing
        if (!S.arePaired(ws.deviceId, m.hostId)) return send(ws, { type: 'join-failed', reason: 'not-paired' });
        hostId = m.hostId;
      } else {
        return send(ws, { type: 'join-failed', reason: 'no-target' });
      }

      const grant = S.findGrant(hostId, ws.deviceId);
      const unattended = !!grant;

      const req = {
        type: 'join-request',
        controllerId: ws.deviceId,
        unattended,
        grantId: grant?.grantId,
      };

      const host = S.devices.get(hostId);
      if (host) {
        send(host, req);
      } else {
        await wakeDevice(hostId, { controllerId: ws.deviceId, sessionId: m.code, unattended });
        S.pendingWakes.set(hostId, {
          controllerId: ws.deviceId,
          code: m.code,
          unattended,
          expires: Date.now() + cfg.pendingWakeTtlMs,
        });
      }
      send(ws, { type: 'join-pending', hostId, unattended });
      break;
    }

    // ---- host consent result (attended) ----
    case 'consent': {
      if (!ws.deviceId) return send(ws, { type: 'error', reason: 'not-registered' });
      send(S.devices.get(m.controllerId), {
        type: 'consent',
        accepted: m.accepted,
        hostId: ws.deviceId,
      });
      break;
    }

    // ---- WebRTC handshake relay ----
    case 'offer':
    case 'answer':
    case 'ice':
    case 'restart':
    case 'hangup': {
      if (!ws.deviceId) return send(ws, { type: 'error', reason: 'not-registered' });
      send(S.devices.get(m.to), { ...m, from: ws.deviceId });
      break;
    }

    // ---- unattended grants ----
    case 'register-grant': {
      if (!ws.deviceId) return send(ws, { type: 'error', reason: 'not-registered' });
      if (!S.grants.has(ws.deviceId)) S.grants.set(ws.deviceId, new Map());
      S.grants.get(ws.deviceId).set(m.grant.grantId, m.grant);
      send(ws, { type: 'grant-registered', grantId: m.grant.grantId });
      break;
    }
    case 'revoke-grant': {
      if (!ws.deviceId) return send(ws, { type: 'error', reason: 'not-registered' });
      S.grants.get(ws.deviceId)?.delete(m.grantId);
      break;
    }

    // ---- pairing graph ----
    case 'register-pairing': {
      // The graph is keyed on authenticated ids; only allow self -> peer edges.
      if (!ws.deviceId || m.myPub !== ws.deviceId) return;
      S.linkPair(m.myPub, m.peerPub);
      S.linkPair(m.peerPub, m.myPub);
      send(ws, { type: 'pairing-registered', peerPub: m.peerPub });
      break;
    }
    case 'revoke-pairing': {
      if (!ws.deviceId || m.myPub !== ws.deviceId) return;
      S.unlinkPair(m.myPub, m.peerPub);
      S.unlinkPair(m.peerPub, m.myPub);
      break;
    }

    // ---- pairing handshake + per-session auth relay ----
    case 'pair-complete':
    case 'pair-ack':
    case 'auth-challenge': {
      if (!ws.deviceId) return send(ws, { type: 'error', reason: 'not-registered' });
      send(S.devices.get(m.to), { ...m, from: ws.deviceId });
      break;
    }

    default:
      // ignore unknown types
      break;
  }
}

module.exports = { start, handle, send, code6 };

if (require.main === module) {
  start();
}
