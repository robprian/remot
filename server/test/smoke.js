'use strict';

// End-to-end smoke test of the signaling broker: spins up the server, connects
// a "host" and a "controller" (with REAL EC device identities + signed
// registration), and exercises pairing, direct dial, join-request, consent,
// offer/answer/ice relay, rate limiting, and the health endpoint. No
// Android/WebRTC involved — pure protocol.

const crypto = require('crypto');
const http = require('http');
const WebSocket = require('ws');
const { start } = require('../src/server');
const S = require('../src/state');

const PORT = process.env.PORT || 8080;
const URL = `ws://localhost:${PORT}`;

// ---- real device identities (P-256) ---------------------------------------
function makeDevice() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
  });
  const pubDer = publicKey.export({ type: 'spki', format: 'der' });
  return {
    deviceId: crypto.createHash('sha256').update(pubDer).digest('hex'),
    pubB64: pubDer.toString('base64'),
    privateKey,
  };
}
const HOST_DEV = makeDevice();
const CTRL_DEV = makeDevice();

function signNonce(privateKey, nonceB64) {
  const signer = crypto.createSign('SHA256');
  signer.update(Buffer.from(nonceB64, 'base64'));
  return signer.sign({ key: privateKey, dsaEncoding: 'der' }).toString('base64');
}

// ---- protocol helpers ------------------------------------------------------
/** Open a socket and complete authenticated registration. */
function open(dev) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    ws.on('open', () =>
      ws.send(JSON.stringify({ type: 'register', deviceId: dev.deviceId, pubKeyB64: dev.pubB64 }))
    );
    ws.on('message', (raw) => {
      const m = JSON.parse(raw.toString());
      // Only answer SERVER-issued registration challenges (no `from`). A relayed
      // peer challenge carries `from` and must NOT be answered as registration.
      if (m.type === 'auth-challenge' && !m.from) {
        ws.send(JSON.stringify({ type: 'auth-response', nonce: m.nonce, sig: signNonce(dev.privateKey, m.nonce) }));
      } else if (m.type === 'registered') {
        resolve({ ws, first: m });
      } else if (m.type === 'register-failed') {
        reject(new Error(`register failed: ${m.reason}`));
      }
    });
    ws.on('error', reject);
  });
}
/** Open a raw socket without registering (for negative tests). */
function openRaw() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}
function next(ws, type) {
  return new Promise((resolve) => {
    const on = (raw) => {
      const m = JSON.parse(raw.toString());
      if (!type || m.type === type) { ws.off('message', on); resolve(m); }
    };
    ws.on('message', on);
  });
}
/** Collect exactly `count` messages and resolve with the array. */
function collect(ws, count) {
  return new Promise((resolve) => {
    const msgs = [];
    const on = (raw) => {
      msgs.push(JSON.parse(raw.toString()));
      if (msgs.length >= count) { ws.off('message', on); resolve(msgs); }
    };
    ws.on('message', on);
  });
}
const send = (ws, o) => ws.send(JSON.stringify(o));

let passed = 0, failed = 0;
function check(name, cond) {
  if (cond) { passed++; console.log(`  ✓ ${name}`); }
  else { failed++; console.log(`  ✗ ${name}`); }
}

(async () => {
  const server = start();
  await new Promise((r) => setTimeout(r, 200));

  const host = await open(HOST_DEV);
  const ctrl = await open(CTRL_DEV);
  check('both devices register with signed challenge', host.first.deviceId === HOST_DEV.deviceId && ctrl.first.deviceId === CTRL_DEV.deviceId);
  check('register returns iceServers', Array.isArray(host.first.iceServers) && host.first.iceServers.length >= 1);

  // --- registration authentication: bad key / id mismatch rejected ---
  {
    const imposter = await openRaw();
    const badKey = makeDevice(); // key whose id does NOT match the claimed id
    const failP = next(imposter, 'register-failed');
    send(imposter, { type: 'register', deviceId: HOST_DEV.deviceId, pubKeyB64: badKey.pubB64 });
    const fail = await failP;
    check('register with mismatched key/id rejected', fail.reason === 'bad-key');
    imposter.close();
  }
  {
    const liar = await openRaw();
    const failP = next(liar, 'register-failed');
    send(liar, { type: 'register', deviceId: 'deadbeef'.repeat(8), pubKeyB64: HOST_DEV.pubB64 });
    const fail = await failP;
    check('register with forged id rejected', fail.reason === 'bad-key');
    liar.close();
  }
  {
    const noSig = await openRaw();
    const failP = next(noSig, 'register-failed');
    send(noSig, { type: 'register', deviceId: CTRL_DEV.deviceId, pubKeyB64: CTRL_DEV.pubB64 });
    const chal = await next(noSig, 'auth-challenge');
    check('register issues auth-challenge', typeof chal.nonce === 'string' && chal.nonce.length > 0);
    // Answer with a signature over the WRONG nonce -> must be rejected.
    send(noSig, { type: 'auth-response', nonce: 'd3Jvbmc=', sig: signNonce(CTRL_DEV.privateKey, 'd3Jvbmc=') });
    const fail = await failP;
    check('auth-response with wrong nonce rejected', fail.reason === 'auth-failed');
    noSig.close();
  }
  {
    // The registration auth-response MUST echo the challenge nonce — a response
    // with a VALID signature but NO nonce field is rejected (the exact bug that
    // made the Android client always fail registration with auth-failed).
    const noNonce = await openRaw();
    const failP = next(noNonce, 'register-failed');
    send(noNonce, { type: 'register', deviceId: HOST_DEV.deviceId, pubKeyB64: HOST_DEV.pubB64 });
    const chal = await next(noNonce, 'auth-challenge');
    const sig = signNonce(HOST_DEV.privateKey, chal.nonce);
    send(noNonce, { type: 'auth-response', sig }); // no nonce echoed
    const fail = await failP;
    check('auth-response WITHOUT echoed nonce rejected', fail.reason === 'auth-failed');
    noNonce.close();
  }

  // --- health endpoint ---
  {
    const body = await new Promise((resolve) => {
      http.get(`http://localhost:${PORT}/healthz`, (res) => {
        let data = '';
        res.on('data', (c) => { data += c; });
        res.on('end', () => resolve({ status: res.statusCode, data: JSON.parse(data) }));
      });
    });
    check('health endpoint returns 200 ok', body.status === 200 && body.data.status === 'ok');
  }

  // --- pairing graph ---
  send(ctrl.ws, { type: 'register-pairing', myPub: CTRL_DEV.deviceId, peerPub: HOST_DEV.deviceId });
  const paired = await next(ctrl.ws, 'pairing-registered');
  check('pairing registered', paired.peerPub === HOST_DEV.deviceId);

  // forging a pairing for another device must be ignored
  {
    const fake = await openRaw();
    send(fake, { type: 'register-pairing', myPub: HOST_DEV.deviceId, peerPub: CTRL_DEV.deviceId });
    const timeout = new Promise((r) => setTimeout(() => r('timeout'), 300));
    const resp = await Promise.race([next(fake, 'pairing-registered'), timeout]);
    check('pairing forged on behalf of another device rejected', resp === 'timeout');
    fake.close();
  }

  // --- direct dial to paired host produces a join-request on the host ---
  const jrP = next(host.ws, 'join-request');
  send(ctrl.ws, { type: 'join', hostId: HOST_DEV.deviceId });
  const jr = await jrP;
  check('host receives join-request', jr.controllerId === CTRL_DEV.deviceId);
  check('join not unattended (no grant yet)', jr.unattended === false);

  // --- register an unattended grant, dial again -> unattended:true ---
  send(host.ws, {
    type: 'register-grant',
    grant: { grantId: 'g1', controllerId: CTRL_DEV.deviceId, active: true, expiresAt: null },
  });
  await next(host.ws, 'grant-registered');
  const jr2P = next(host.ws, 'join-request');
  send(ctrl.ws, { type: 'join', hostId: HOST_DEV.deviceId });
  const jr2 = await jr2P;
  check('join now unattended (grant present)', jr2.unattended === true && jr2.grantId === 'g1');

  // --- consent relay host -> controller ---
  const consentP = next(ctrl.ws, 'consent');
  send(host.ws, { type: 'consent', controllerId: CTRL_DEV.deviceId, accepted: true });
  const consent = await consentP;
  check('controller receives consent', consent.accepted === true && consent.hostId === HOST_DEV.deviceId);

  // --- offer/answer/ice relay with from-stamping ---
  const offerP = next(ctrl.ws, 'offer');
  send(host.ws, { type: 'offer', to: CTRL_DEV.deviceId, sdp: 'SDP_OFFER' });
  const offer = await offerP;
  check('offer relayed with from stamp', offer.sdp === 'SDP_OFFER' && offer.from === HOST_DEV.deviceId);

  const iceP = next(host.ws, 'ice');
  send(ctrl.ws, { type: 'ice', to: HOST_DEV.deviceId, mid: '0', index: 0, cand: 'candidate:...' });
  const ice = await iceP;
  check('ice relayed', ice.cand === 'candidate:...' && ice.from === CTRL_DEV.deviceId);

  // --- per-session auth relay (pairing handshake style) ---
  const authP = next(ctrl.ws, 'auth-challenge');
  send(host.ws, { type: 'auth-challenge', to: CTRL_DEV.deviceId, nonce: 'bm9uY2U=' });
  const auth = await authP;
  check('auth-challenge relayed', auth.nonce === 'bm9uY2U=' && auth.from === HOST_DEV.deviceId);

  // --- code-based join path ---
  const codeP = next(host.ws, 'session-code');
  send(host.ws, { type: 'host-open' });
  const codeMsg = await codeP;
  check('host-open returns 6-digit code', /^\d{6}$/.test(codeMsg.code));

  const jr3P = next(host.ws, 'join-request');
  send(ctrl.ws, { type: 'join', code: codeMsg.code });
  const jr3 = await jr3P;
  check('code join reaches host', jr3.controllerId === CTRL_DEV.deviceId);

  // --- invalid code rejected ---
  const failP = next(ctrl.ws, 'join-failed');
  send(ctrl.ws, { type: 'join', code: '000000' });
  const fail = await failP;
  check('invalid code rejected', fail.reason === 'invalid-code');

  // --- rate limiting: excess joins per IP rejected ---
  {
    S.joinAttempts.clear();
    const got = collect(ctrl.ws, 11);
    for (let i = 0; i < 11; i++) send(ctrl.ws, { type: 'join', hostId: 'ghost-host-000' });
    const msgs = await got;
    check('rate limit rejects excess joins', msgs.some((m) => m.type === 'join-failed' && m.reason === 'rate-limited'));
  }

  // --- app-level heartbeat: ping only answered for REGISTERED sockets ---
  {
    const pongP = next(host.ws, 'pong');
    send(host.ws, { type: 'ping', ts: 12345 });
    const pong = await pongP;
    check('registered socket receives pong', pong.type === 'pong' && pong.ts === 12345);
  }
  {
    // An unauthenticated socket must NOT get its application ping answered
    // (the client is not supposed to ping before registering anyway).
    const raw = await openRaw();
    const timeout = new Promise((r) => setTimeout(() => r('timeout'), 300));
    const resp = await Promise.race([next(raw, 'pong'), timeout]);
    check('unregistered socket ping NOT answered', resp === 'timeout');
    raw.close();
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  host.ws.close(); ctrl.ws.close(); server.close();
  process.exit(failed === 0 ? 0 : 1);
})();
