# Remot — Protocol Reference

Two protocols are involved:

1. **Signaling protocol** — JSON messages between the app and the signaling
   server (WebSocket, `wss://` in production).
2. **Control protocol** — JSON messages on the WebRTC `control` DataChannel,
   controller → host, carrying input actions.

All coordinates are **normalized 0..1 fractions** of the host's screen, so
different resolutions and aspect ratios map correctly.

---

## 1. Signaling protocol

Transport: WebSocket, one JSON object per message. The server never sees media.

### 1.1 Registration (authenticated)

```
C→S  {"type":"register","deviceId":"<hex sha256 of pubkey>","pubKeyB64":"<DER pubkey b64>"}
S→C  {"type":"auth-challenge","nonce":"<b64 32-byte nonce>"}
C→S  {"type":"auth-response","sig":"<b64 ECDSA/SHA256 over nonce bytes>"}     // no `to`
S→C  {"type":"registered","deviceId":"<id>","iceServers":[{urls,username,credential},…]}
S→C  {"type":"register-failed","reason":"bad-key|bad-id|auth-failed|no-challenge|…"}  // then close
```

The server verifies `deviceId == SHA-256(pubKey)` and the signature before
routing anything to this socket.

> Note: `auth-challenge`/`auth-response` are ALSO used peer-to-peer for
> per-session identity proofs. Those relayed messages carry a `from`/`to`;
> registration challenges carry neither `from` (server-issued) nor `to`
> (direct).

### 1.2 Device wake / FCM

```
C→S  {"type":"report-token","fcmToken":"<token>"}
```

### 1.3 Host opens an attended session

```
H→S  {"type":"host-open"}
S→H  {"type":"session-code","code":"123456"}          // TTL 5 min, one-time use
```

### 1.4 Join (code or paired-direct)

```
C→S  {"type":"join","code":"123456"}
C→S  {"type":"join","hostId":"<host deviceId>"}        // requires existing pairing
S→C  {"type":"join-failed","reason":"invalid-code|not-paired|no-target|rate-limited|not-registered"}
S→C  {"type":"join-pending","hostId":"<id>","unattended":bool}
S→H  {"type":"join-request","controllerId":"<id>","unattended":bool,"grantId":"<id>|null"}
```

If the host is offline, the server sends an FCM data-only wake and queues the
join-request until the host re-registers (TTL 60 s).

### 1.5 Host consent (attended)

```
H→S  {"type":"consent","controllerId":"<id>","accepted":bool}
S→C  {"type":"consent","accepted":bool,"hostId":"<id>"}
```

### 1.6 WebRTC handshake relay (from-stamped)

```
X→S  {"type":"offer","to":"<peer>","sdp":"…","fpSig":"<b64 sig over DTLS fingerprint>"}
S→Y  {…same…,"from":"<X>"}
X→S  {"type":"answer","to":"<peer>","sdp":"…","fpSig":"…"}
X→S  {"type":"ice","to":"<peer>","mid":"0","index":0,"cand":"candidate:…"}
X→S  {"type":"restart","to":"<peer>"}        // request ICE restart
X→S  {"type":"hangup","to":"<peer>"}
```

`fpSig` is the sender's ECDSA signature over the SDP `a=fingerprint` line —
MITM protection for paired sessions.

### 1.7 Pairing graph (authenticated by deviceId)

```
X→S  {"type":"register-pairing","myPub":"<own id>","peerPub":"<peer id>"}
S→X  {"type":"pairing-registered","peerPub":"<peer id>"}
X→S  {"type":"revoke-pairing","myPub":"<own id>","peerPub":"<peer id>"}
```

The server only accepts `myPub == the authenticated deviceId` of the sender.

### 1.8 Pairing handshake (relayed)

```
C→S→H  {"type":"pair-complete","to":"<host id>","controllerPub":"<b64>",
         "controllerEph":"<b64>","proof":"<b64 sig>","nonce":"<b64>"}
H→S→C  {"type":"pair-ack","to":"<ctrl id>","hostProof":"<b64 sig>"}
```

### 1.9 Per-session auth (relayed)

```
H→S→C  {"type":"auth-challenge","to":"<ctrl>","nonce":"<b64>"}
C→S→H  {"type":"auth-response","to":"<host>","sig":"<b64>"}
```

### 1.10 Unattended grants

```
H→S  {"type":"register-grant","grant":{"grantId","controllerId","active","expiresAt",…}}
S→H  {"type":"grant-registered","grantId":"<id>"}
H→S  {"type":"revoke-grant","grantId":"<id>"}
```

### 1.11 TURN credentials

```
C→S  {"type":"turn-credentials"}
S→C  {"type":"turn-credentials","iceServers":[…]}
```

### 1.12 Health

```
GET /healthz  →  200 {"status":"ok","uptimeSec":…,"devices":…}
```

---

## 2. Control protocol (WebRTC DataChannel `"control"`)

Ordered, reliable DataChannel. Controller → host only. All coordinates are
normalized 0..1; the host multiplies by its own screen size. Orientation and
aspect differences are handled by mapping into the fitted video rect before
sending.

### Messages

| `t` | Fields | Description |
| --- | --- | --- |
| `tap` | `x`, `y` | Tap at (x, y) |
| `long-press` | `x`, `y` | Press and hold ~600 ms at (x, y) |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `ms` | Swipe/drag from (x1,y1) to (x2,y2) over `ms` (50–2000) |
| `key` | `k` | Global action: `BACK`, `HOME`, `RECENTS` |
| `text` | `s` | Insert text into the focused input field on the host |

### Example

```json
{"t":"tap","x":0.52,"y":0.41}
{"t":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2,"ms":300}
{"t":"key","k":"HOME"}
{"t":"text","s":"hello"}
```

### Host dispatch (InputRouter)

- `tap` / `long-press` → `AccessibilityService.dispatchGesture` (50 ms / 600 ms
  stroke).
- `swipe` → `dispatchGesture` with a move path over `ms`.
- `key` → `performGlobalAction`.
- `text` → `ACTION_SET_TEXT` on the focused editable node.

Unknown `t` values are ignored. If the host's session is view-only (locked or
grant-scope without CONTROL), input is dropped (`InputRouter.controlEnabled`).

---

## 3. Versioning

This protocol is stable at V1C001. Any breaking change (new required fields,
removed message types, changed semantics) MUST bump the server/app major
version and be recorded in `CHANGELOG.md`.
