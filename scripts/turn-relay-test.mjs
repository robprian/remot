#!/usr/bin/env node
/**
 * Real TURN relay-media verification (the FULL relay path, not just an Allocate
 * 401 challenge). Proves that the relay socket range (default UDP 49152–65535)
 * is actually open and forwarding media, which probe-endpoints.mjs does not.
 *
 * Steps (control socket owns the allocation):
 *   1. Allocate a relay with real short-lived credentials (auth-secret HMAC,
 *      identical scheme to server/src/turn.js and the app's StunTurnProbe),
 *      reading XOR-RELAYED-ADDRESS from the success response.
 *   2. Assert the relayed port falls inside TURN_MIN_PORT..TURN_MAX_PORT — the
 *      range that must be open in the cloud security group.
 *   3. The peer socket discovers its own public IP with a STUN Binding to the
 *      same server (XOR-MAPPED-ADDRESS), and the control socket grants a
 *      CreatePermission for that IP (RFC 5766 §9.2 — without it coturn
 *      silently drops the peer's datagram).
 *   4. The peer sends a UDP datagram straight AT the relayed address; coturn
 *      relays it back to the control socket as a TURN DATA indication. If the
 *      exact payload arrives intact, real relay media works — proof the range
 *      forwards media, not merely that Allocate succeeds.
 *
 * Env (no hardcoded addresses):
 *   TURN_HOST        TURN/STUN host (direct public IP)
 *   TURN_PORT        default 3478
 *   TURN_REALM       banner only; the realm is auto-detected from the 401
 *   TURN_SECRET      coturn static-auth-secret (same value as server TURN_SECRET)
 *   TURN_MIN_PORT    default 49152
 *   TURN_MAX_PORT    default 65535
 *
 * Exit: 0 = relay allocated AND media round-trip verified; non-zero otherwise.
 */
import dgram from 'node:dgram';
import crypto from 'node:crypto';

const HOST = process.env.TURN_HOST || '';
const PORT = parseInt(process.env.TURN_PORT || '3478', 10);
const REALM = process.env.TURN_REALM || HOST;
const SECRET = process.env.TURN_SECRET || '';
const MIN_PORT = parseInt(process.env.TURN_MIN_PORT || '49152', 10);
const MAX_PORT = parseInt(process.env.TURN_MAX_PORT || '65535', 10);
const TIMEOUT = 6000;

const COOKIE = 0x2112a442;
const A = {
  USERNAME: 0x0006,
  MESSAGE_INTEGRITY: 0x0008,
  ERROR_CODE: 0x0009,
  REALM: 0x0014,
  NONCE: 0x0015,
  XOR_PEER_ADDRESS: 0x0012,
  REQUESTED_TRANSPORT: 0x0019,
  XOR_RELAYED_ADDRESS: 0x0016,
  XOR_MAPPED_ADDRESS: 0x0020,
  DATA: 0x0013, // RFC 5766 — DATA attribute (0x0017 is the DATA-indication *message* type)
};
const T = {
  BINDING: 0x0001,
  BINDING_SUCCESS: 0x0101,
  ALLOCATE: 0x0003,
  CREATE_PERMISSION: 0x0008,
  CREATE_PERMISSION_SUCCESS: 0x0108,
  SUCCESS: 0x0103,
  ERROR: 0x0113,
  DATA_IND: 0x0017,
};

function pad4(n) { return (4 - (n % 4)) % 4; }

function mkAttr(type, value) {
  const padded = pad4(value.length);
  const buf = Buffer.alloc(4 + value.length + padded);
  buf.writeUInt16BE(type, 0);
  buf.writeUInt16BE(value.length, 2);
  value.copy(buf, 4);
  return buf;
}

function parseAttrs(msg) {
  const out = {};
  let off = 20;
  while (off + 4 <= msg.length) {
    const type = msg.readUInt16BE(off);
    const len = msg.readUInt16BE(off + 2);
    if (off + 4 + len > msg.length) break;
    out[type] = Buffer.from(msg.subarray(off + 4, off + 4 + len));
    if (type === A.MESSAGE_INTEGRITY) break;
    off += 4 + len + pad4(len);
  }
  return out;
}

function decXorAddr(val) {
  const port = val.readUInt16BE(2) ^ ((COOKIE >> 16) & 0xffff);
  const ip = [0, 1, 2, 3].map((i) => val[i + 4] ^ ((COOKIE >> ((3 - i) * 8)) & 0xff)).join('.');
  return { port, ip };
}

function encXorAddr({ ip, port }) {
  const b = Buffer.alloc(8);
  b[0] = 0;
  b[1] = 0x01; // IPv4
  b.writeUInt16BE(port ^ ((COOKIE >> 16) & 0xffff), 2);
  const oct = ip.split('.').map(Number);
  for (let i = 0; i < 4; i++) b[4 + i] = oct[i] ^ ((COOKIE >> ((3 - i) * 8)) & 0xff);
  return b;
}

/**
 * Send `msg` on `sock` and wait for a response whose transaction id matches
 * `txn`. If `wantData` is set, also watch for a DATA indication carrying that
 * exact payload (used for the media round-trip).
 */
function sendWait(sock, msg, txn, wantData) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve({ timeout: true }), TIMEOUT);
    const onMsg = (data) => {
      if (data.length < 20) return;
      const t0 = data.readUInt16BE(0);
      const rid = data.subarray(8, 20);
      if (rid.equals(txn)) {
        clearTimeout(timer);
        sock.off('message', onMsg);
        resolve({ t0, attrs: parseAttrs(data) });
      } else if (wantData && t0 === T.DATA_IND) {
        const dv = parseAttrs(data)[A.DATA];
        if (dv && dv.toString() === wantData) {
          clearTimeout(timer);
          sock.off('message', onMsg);
          resolve({ dataOk: true });
        }
      }
    };
    sock.on('message', onMsg);
    sock.send(msg, PORT, HOST, (e) => { if (e) { clearTimeout(timer); sock.off('message', onMsg); resolve({ sendErr: e.message }); } });
  });
}

/**
 * Build a STUN/TURN request. MESSAGE-INTEGRITY is appended with a placeholder,
 * the HMAC is computed over header+attrs (RFC 5389 §15.4 — length already
 * covers the MI attribute), then the real digest replaces the placeholder.
 */
function buildRequest(type, txn, opts) {
  const attrs = [];
  if (opts.peer) attrs.push(mkAttr(A.XOR_PEER_ADDRESS, encXorAddr(opts.peer)));
  if (opts.extra) attrs.push(...opts.extra);
  if (!opts.noTransport) attrs.push(mkAttr(A.REQUESTED_TRANSPORT, Buffer.from([17, 0, 0, 0])));
  if (opts.username) attrs.push(mkAttr(A.USERNAME, Buffer.from(opts.username)));
  if (opts.realm) attrs.push(mkAttr(A.REALM, Buffer.from(opts.realm)));
  if (opts.nonce) attrs.push(mkAttr(A.NONCE, Buffer.from(opts.nonce)));
  let body = Buffer.concat(attrs);
  if (opts.key) {
    body = Buffer.concat([body, Buffer.alloc(24)]);
    body.writeUInt16BE(A.MESSAGE_INTEGRITY, body.length - 24);
    body.writeUInt16BE(20, body.length - 22);
  }
  const msg = Buffer.alloc(20 + body.length);
  msg.writeUInt16BE(type, 0);
  msg.writeUInt16BE(body.length, 2);
  msg.writeUInt32BE(COOKIE, 4);
  txn.copy(msg, 8);
  body.copy(msg, 20);
  if (opts.key) {
    const hmac = crypto.createHmac('sha1', opts.key).update(msg.subarray(0, msg.length - 24)).digest();
    hmac.copy(msg, msg.length - 20);
  }
  return msg;
}

function shortCreds() {
  const expiry = Math.floor(Date.now() / 1000) + 3600;
  const username = `${expiry}:remot`;
  const password = crypto.createHmac('sha1', SECRET).update(username).digest('base64');
  return { username, password };
}

async function allocate(sock) {
  // unauth -> 401 (realm+nonce)
  const t = crypto.randomBytes(12);
  const ch = await sendWait(sock, buildRequest(T.ALLOCATE, t, {}), t);
  if (ch.timeout) return { err: 'timeout: coturn did not answer Allocate (isolated?)' };
  if (ch.sendErr) return { err: 'send failed: ' + ch.sendErr };
  if (ch.t0 !== T.ERROR) return { err: 'expected 401, got type 0x' + ch.t0.toString(16) };
  const realm = ch.attrs[A.REALM]?.toString() || '';
  const nonce = ch.attrs[A.NONCE]?.toString() || '';
  if (!realm || !nonce) return { err: '401 missing realm/nonce' };

  const { username, password } = shortCreds();
  const key = crypto.createHash('md5').update(`${username}:${realm}:${password}`).digest();
  const t2 = crypto.randomBytes(12);
  const res = await sendWait(sock, buildRequest(T.ALLOCATE, t2, { username, realm, nonce, key }), t2);
  if (res.timeout) return { err: 'timeout: authenticated Allocate unanswered' };
  if (res.t0 === T.SUCCESS) {
    const rad = res.attrs[A.XOR_RELAYED_ADDRESS];
    return rad ? { relay: decXorAddr(rad), auth: { username, realm, nonce, key } } : { err: 'success without XOR-RELAYED-ADDRESS' };
  }
  return { err: 'Allocate rejected (0x' + (res.t0 || 0).toString(16) + ')' };
}

async function peerPublicIp(peer) {
  const t = crypto.randomBytes(12);
  const res = await sendWait(peer, buildRequest(T.BINDING, t, { noTransport: true }), t);
  if (res.timeout || res.sendErr) { console.error('  [debug] peerPublicIp:', res.timeout ? 'timeout' : res.sendErr); return null; }
  if (res.t0 !== T.BINDING_SUCCESS) {
    const ec = res.attrs ? res.attrs[A.ERROR_CODE] : null;
    if (ec && ec.length >= 4) {
      const code = (ec.readUInt16BE(2) & 0x7) * 100 + ec.readUInt8(3);
      console.error('  [debug] peerPublicIp: STUN error ' + code + ' ' + ec.subarray(4).toString());
    } else {
      console.error('  [debug] peerPublicIp: unexpected type 0x' + (res.t0 || 0).toString(16));
    }
    return null;
  }
  const ma = res.attrs[A.XOR_MAPPED_ADDRESS];
  return ma ? decXorAddr(ma) : null;
}

async function createPermission(sock, peerAddr, auth) {
  const t = crypto.randomBytes(12);
  const res = await sendWait(sock, buildRequest(T.CREATE_PERMISSION, t, {
    peer: peerAddr, username: auth.username, realm: auth.realm, nonce: auth.nonce, key: auth.key,
    noTransport: true,
  }), t);
  if (res.timeout) return { err: 'timeout: CreatePermission unanswered' };
  if (res.sendErr) return { err: 'send failed: ' + res.sendErr };
  if (res.t0 !== T.CREATE_PERMISSION_SUCCESS) {
    const ec = res.attrs ? res.attrs[A.ERROR_CODE] : null;
    if (ec && ec.length >= 4) {
      const code = (ec.readUInt16BE(2) & 0x7) * 100 + ec.readUInt8(3);
      return { err: 'CreatePermission rejected: STUN error ' + code + ' ' + ec.subarray(4).toString() };
    }
    return { err: 'CreatePermission rejected (0x' + (res.t0 || 0).toString(16) + ')' };
  }
  return {};
}

async function main() {
  if (!HOST) { console.error('TURN_HOST required'); process.exit(1); }
  if (!SECRET) { console.error('TURN_SECRET required'); process.exit(1); }

  console.log('========================================');
  console.log('REMOT TURN RELAY-MEDIA TEST');
  console.log('========================================');
  console.log(`TURN host   : ${HOST}:${PORT}`);
  console.log(`Relay range : ${MIN_PORT}–${MAX_PORT}`);
  console.log('');

  const sock = dgram.createSocket('udp4');
  sock.bind(0, '0.0.0.0');

  const a = await allocate(sock);
  if (a.err) {
    console.log(`1. Allocate        : FAIL — ${a.err}`);
    try { sock.close(); } catch {}
    console.log('========================================');
    console.log('RESULT: FAIL');
    console.log('========================================');
    process.exit(1);
  }
  const inRange = a.relay.port >= MIN_PORT && a.relay.port <= MAX_PORT;
  console.log(`1. Allocate        : OK  relayed=${a.relay.ip}:${a.relay.port} ` +
    `(in ${MIN_PORT}–${MAX_PORT}: ${inRange ? 'YES' : 'NO'})`);

  // Peer socket: discover its own public IP (as seen by coturn) via STUN.
  const peer = dgram.createSocket('udp4');
  peer.on('error', () => {});
  await new Promise((res) => peer.bind(0, '0.0.0.0', res));
  const pub = await peerPublicIp(peer);
  if (!pub) {
    console.log('2. CreatePermission : FAIL — peer STUN Binding did not return XOR-MAPPED-ADDRESS');
    try { sock.close(); } catch {}
    try { peer.close(); } catch {}
    console.log('========================================');
    console.log('RESULT: FAIL');
    console.log('========================================');
    process.exit(1);
  }
  console.log(`2. Peer public IP  : ${pub.ip}:${pub.port} (STUN Binding)`);

  const cp = await createPermission(sock, pub, a.auth);
  if (cp.err) {
    console.log(`3. CreatePermission : FAIL — ${cp.err}`);
    try { sock.close(); } catch {}
    try { peer.close(); } catch {}
    console.log('========================================');
    console.log('RESULT: FAIL');
    console.log('========================================');
    process.exit(1);
  }
  console.log(`3. CreatePermission: OK  granted for ${pub.ip}`);

  // Media round-trip: peer fires a datagram at the relayed address; coturn
  // relays it back to the control socket (the allocation owner) as DATA ind.
  const payload = `remot-relay-${Date.now()}-${crypto.randomBytes(4).toString('hex')}`;
  peer.send(Buffer.from(payload), a.relay.port, a.relay.ip);
  const gotBack = await new Promise((resolve) => {
    const timer = setTimeout(() => { try { sock.close(); } catch {} resolve(false); }, TIMEOUT + 2000);
    const onMsg = (data) => {
      if (data.length < 20) return;
      const t0 = data.readUInt16BE(0);
      if (t0 !== T.DATA_IND) { console.error('  [debug] control rx type=0x' + t0.toString(16) + ' len=' + data.length); return; }
      if (process.env.TURN_DEBUG) console.error('  [debug] DATA_IND hex: ' + data.subarray(0, 64).toString('hex'));
      const dv = parseAttrs(data)[A.DATA];
      const got = dv ? dv.toString() : '';
      if (dv && got === payload) {
        clearTimeout(timer);
        sock.off('message', onMsg);
        resolve(true);
      } else {
        console.error('  [debug] DATA_IND payload mismatch: ' + got.length + ' bytes');
      }
    };
    sock.on('message', onMsg);
    sock.on('error', () => { clearTimeout(timer); resolve(false); });
  });
  try { sock.close(); } catch {}
  try { peer.close(); } catch {}

  if (gotBack) {
    console.log('4. Media relay     : OK  peer datagram relayed back through the relay');
  } else {
    console.log('4. Media relay     : FAIL — datagram sent to relayed addr never came back');
    console.log('   (security group likely drops UDP ' + MIN_PORT + '–' + MAX_PORT + ')');
  }
  console.log('========================================');
  const pass = gotBack && inRange;
  console.log(pass ? 'RESULT: PASS — relay media round-trip works; UDP range OPEN'
    : 'RESULT: FAIL — relay media not verified end-to-end');
  console.log('========================================');
  process.exit(pass ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
