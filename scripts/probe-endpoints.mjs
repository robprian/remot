#!/usr/bin/env node
/**
 * External endpoint probe for Remot's production infrastructure.
 *
 * Intended to run on a GitHub-hosted runner (public internet) so the result is
 * a genuine "from outside the VPS" check. Endpoints come from environment
 * variables (GitHub secrets) — nothing is hardcoded:
 *
 *   SERVER_URL  e.g. ws://turn.example.com:8080   (signaling / ws endpoint)
 *   SERVER_IP   e.g. 203.0.113.10                 (direct public IP fallback)
 *
 * Checks:
 *   1. TCP connect  :8080            (signaling)
 *   2. HTTP  GET /healthz            (signaling HTTP)
 *   3. WebSocket handshake           (upgrade -> 101)
 *   4. WebSocket register            (full server message loop: register-failed)
 *   5. STUN binding (UDP 3478)       (coturn round-trip, reported only)
 *   6. TURN TLS port (TCP 5349)      (reported only)
 *
 * Exit codes: 0 = signaling checks pass (STUN/TURN are reported, not fatal);
 *             1 = signaling (TCP/HTTP/WS/register) failed.
 */
import net from 'node:net';
import http from 'node:http';
import dgram from 'node:dgram';
import crypto from 'node:crypto';

const SERVER_URL = process.env.SERVER_URL || '';
const SERVER_IP = process.env.SERVER_IP || '';
const STUN_PORT = 3478;
const TURN_TLS_PORT = 5349;

function ok(label, detail = '') {
  console.log(`  OK    ${label}${detail ? ' — ' + detail : ''}`);
}
function fail(label, detail = '') {
  console.log(`  FAIL  ${label}${detail ? ' — ' + detail : ''}`);
}

function parseUrl(u) {
  const m = /^(ws|wss):\/\/([^:/]+)(?::(\d+))?/.exec(u);
  if (!m) return null;
  return { scheme: m[1], host: m[2], port: parseInt(m[3] || (m[1] === 'wss' ? 443 : 80), 10) };
}

function tcp(host, port, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const s = net.connect({ host, port });
    const t = setTimeout(() => { s.destroy(); resolve({ open: false, err: 'timeout' }); }, timeoutMs);
    s.on('connect', () => { clearTimeout(t); s.destroy(); resolve({ open: true }); });
    s.on('error', (e) => { clearTimeout(t); resolve({ open: false, err: e.code || e.message }); });
  });
}

function httpGet(host, port, path, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const req = http.get({ host, port, path, timeout: timeoutMs }, (res) => {
      let body = '';
      res.on('data', (d) => { body += d; });
      res.on('end', () => resolve({ status: res.statusCode, body: body.slice(0, 120) }));
    });
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: 'timeout' }); });
    req.on('error', (e) => resolve({ status: 0, body: e.code || e.message }));
  });
}

function wsHandshake(host, port, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const req = http.request({
      host, port,
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'),
      },
    });
    let done = false;
    const finish = (r) => { if (!done) { done = true; resolve(r); } };
    // A successful upgrade arrives via the 'upgrade' event (not 'response').
    req.on('upgrade', (res, socket) => { socket.destroy(); finish({ status: 101 }); });
    req.on('response', (res) => { res.destroy(); finish({ status: res.statusCode }); });
    req.setTimeout(timeoutMs, () => { req.destroy(); finish({ status: 0 }); });
    req.on('error', () => finish({ status: 0 }));
    req.end();
  });
}

/** Client->server text frame (masked). */
function encodeFrame(text) {
  const payload = Buffer.from(text);
  const mask = crypto.randomBytes(4);
  const header = [0x81];
  const len = payload.length;
  if (len < 126) {
    header.push(0x80 | len);
  } else if (len < 65536) {
    header.push(0x80 | 126, (len >> 8) & 0xff, len & 0xff);
  } else {
    header.push(0x80 | 127);
    for (let i = 7; i >= 0; i--) header.push((len / 2 ** (8 * i)) & 0xff);
  }
  const masked = Buffer.alloc(len);
  for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i & 3];
  return Buffer.concat([Buffer.from(header), mask, masked]);
}

/** Server->client frame parse (single text frame, no fragmentation). */
function decodeFrame(buf) {
  const b0 = buf[0];
  const b1 = buf[1];
  if (b0 === undefined || b1 === undefined) return null;
  const opcode = b0 & 0x0f;
  let len = b1 & 0x7f;
  let off = 2;
  if (len === 126) {
    if (buf.length < 4) return null;
    len = buf.readUInt16BE(2);
    off = 4;
  } else if (len === 127) {
    if (buf.length < 10) return null;
    len = Number(buf.readBigUInt64BE(2));
    off = 10;
  }
  if (buf.length < off + len) return null;
  const masked = (b1 & 0x80) !== 0;
  let maskKey = null;
  if (masked) {
    maskKey = buf.subarray(off, off + 4);
    off += 4;
  }
  let data = buf.subarray(off, off + len);
  if (maskKey) {
    const d = Buffer.from(data);
    for (let i = 0; i < d.length; i++) d[i] ^= maskKey[i & 3];
    data = d;
  }
  return { opcode, text: opcode === 1 ? data.toString() : null };
}

/**
 * Full register round-trip: open a WS, send a register with a random id/key,
 * and expect the server to answer (register-failed bad-key proves the whole
 * server message loop is alive from this vantage point).
 */
function wsRegister(host, port, timeoutMs = 6000) {
  return new Promise((resolve) => {
    const req = http.request({
      host, port,
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'),
      },
    });
    let responded = false;
    const done = (r) => { if (!responded) { responded = true; resolve(r); } };

    req.on('upgrade', (res, socket) => {
      const deviceId = crypto.randomBytes(16).toString('hex');
      const pubKeyB64 = crypto.randomBytes(64).toString('base64');
      socket.write(encodeFrame(JSON.stringify({ type: 'register', deviceId, pubKeyB64 })));
      socket.setTimeout(timeoutMs, () => { socket.destroy(); done({ ok: false, reply: 'timeout' }); });
      socket.on('data', (buf) => {
        const msg = decodeFrame(buf);
        if (msg && msg.text) {
          try {
            const m = JSON.parse(msg.text);
            socket.destroy();
            if (m.type === 'register-failed') done({ ok: true, reply: m.type, reason: m.reason });
            else if (m.type === 'auth-challenge') done({ ok: true, reply: m.type });
            else done({ ok: false, reply: m.type });
          } catch { /* partial frame — keep reading */ }
        }
      });
      socket.on('error', () => done({ ok: false, reply: 'socket-error' }));
    });
    req.on('response', (res) => done({ ok: false, reply: 'http-' + res.statusCode }));
    req.on('error', (e) => done({ ok: false, reply: e.code || 'error' }));
    req.end();
  });
}

function stun(host, port, timeoutMs = 4000) {
  return new Promise((resolve) => {
    const s = dgram.createSocket('udp4');
    const txn = crypto.randomBytes(12);
    const msg = Buffer.alloc(20);
    msg.writeUInt16BE(0x0001, 0);          // binding request
    msg.writeUInt16BE(0, 2);               // length 0
    msg.writeUInt32BE(0x2112a442, 4);      // magic cookie
    txn.copy(msg, 8);
    const t0 = Date.now();
    const timer = setTimeout(() => {
      try { s.close(); } catch { /* noop */ }
      resolve({ ok: false, err: 'timeout' });
    }, timeoutMs);
    s.on('message', (data) => {
      clearTimeout(timer);
      const typ = data.readUInt16BE(0);
      try { s.close(); } catch { /* noop */ }
      resolve({ ok: typ === 0x0101, rttMs: Date.now() - t0, type: typ });
    });
    s.on('error', (e) => { clearTimeout(timer); resolve({ ok: false, err: e.code || e.message }); });
    s.send(msg, port, host);
  });
}

async function main() {
  const url = parseUrl(SERVER_URL);
  const host = (url && url.host) || SERVER_IP || '';
  const port = (url && url.port) || 8080;

  console.log('========================================');
  console.log('REMOT EXTERNAL ENDPOINT PROBE');
  console.log('========================================');
  if (!host) {
    fail('no endpoint', 'set SERVER_URL and/or SERVER_IP');
    console.log('========================================');
    process.exit(1);
  }
  console.log(`Signaling : ${host}:${port}  (via ${SERVER_URL || 'SERVER_URL empty'})`);
  console.log(`Direct IP : ${SERVER_IP || '(unset)'}`);
  console.log('');

  let signalingOk = true;

  const t = await tcp(host, port);
  if (t.open) ok('TCP :8080 connect');
  else { fail('TCP :8080 connect', t.err || 'refused'); signalingOk = false; }

  const h = await httpGet(host, port, '/healthz');
  if (h.status === 200 && h.body.includes('"ok"')) ok('HTTP /healthz', `200 ${h.body}`);
  else { fail('HTTP /healthz', h.status ? `HTTP ${h.status}` : h.body); signalingOk = false; }

  const hs = await wsHandshake(host, port);
  if (hs.status === 101) ok('WS handshake', '101 Switching Protocols');
  else { fail('WS handshake', hs.status ? `HTTP ${hs.status}` : 'no response'); signalingOk = false; }

  const reg = await wsRegister(host, port);
  if (reg.ok) ok('WS register loop', `${reg.reply}${reg.reason ? ' (' + reg.reason + ')' : ''}`);
  else { fail('WS register loop', reg.reply || 'no response'); signalingOk = false; }

  const ip = SERVER_IP || host;
  const st = await stun(ip, STUN_PORT);
  if (st.ok) ok('STUN UDP :3478', `${st.rttMs}ms`);
  else fail('STUN UDP :3478', st.err || (st.type !== undefined ? `type 0x${st.type.toString(16)}` : 'no reply'));

  const tt = await tcp(ip, TURN_TLS_PORT);
  if (tt.open) ok('TURN TCP :5349');
  else fail('TURN TCP :5349', tt.err || 'refused');

  console.log('========================================');
  console.log(signalingOk
    ? 'RESULT: signaling REACHABLE (STUN/TURN status above)'
    : 'RESULT: signaling UNREACHABLE from this network');
  console.log('========================================');
  process.exit(signalingOk ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
