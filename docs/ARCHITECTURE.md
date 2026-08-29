# Remot — Architecture

Remot lets one Android device view and control another Android device over the
public Internet. It is a remote-support tool (comparable to AnyDesk/TeamViewer
QuickSupport) built on platform APIs only: MediaProjection for screen capture,
AccessibilityService for input injection, and WebRTC for transport.

This document describes the architecture as implemented in v1.0.0.

---

## 1. System overview

```
                 ┌──────────────────────────────────────────────┐
                 │              Signaling Server (Node.js)       │
                 │  device registration · pairing · session      │
                 │  codes · WebRTC handshake relay · TURN creds  │
                 │  (never sees media)                           │
                 └──────────────┬───────────────────────────────┘
                                │  WSS (TLS) — JSON messages
              ┌─────────────────┴──────────────────┐
              ▼                                    ▼
   ┌────────────────────┐                ┌────────────────────┐
   │ Android Controller │   WebRTC       │   Android Target   │
   │  (Device A)        │◄══════════════►│  (Device B)        │
   │  • remote video    │  DTLS-SRTP     │  • MediaProjection │
   │  • input gestures  │  DataChannel   │    screen capture  │
   │  • control keys    │                │  • Accessibility-  │
   └────────────────────┘                │    Service input   │
                                         └────────────────────┘
                STUN: P2P candidate discovery
                TURN: relay fallback (CGNAT, symmetric NAT)
```

Both roles ship in the same APK. Whichever device calls **Share my screen** is
the *host* (controlled) for that session; the other is the *controller*.
Bidirectional use (A→B and B→A) is two independent sessions.

---

## 2. Android architecture

Single-process Kotlin app, Jetpack Compose, single activity, manual DI via
`ServiceLocator` (an object implementing the signaling `Listener` and fanning
events to the `SessionManager`, `PairingManager`, and UI callbacks).

```
RemoteApp (Application) ── initializes ServiceLocator, FCM token
MainActivity ── Compose host, permission launchers
MainViewModel ── UI state, bridges Compose <-> ServiceLocator
ServiceLocator ── singletons: core, trust, grants, signaling, session, pairing
   ├─ identity/DeviceIdentity   Keystore P-256 keypair; pubkey = device ID
   ├─ crypto/Crypto             ECDSA verify, ECDH, safety numbers
   ├─ trust/TrustStore          encrypted store of paired peers
   ├─ trust/PairingManager      authenticated QR pairing handshake
   ├─ unattended/GrantStore     scoped standing access grants
   ├─ signaling/SignalingClient OkHttp WebSocket + reconnect
   ├─ webrtc/WebRtcCore         PeerConnectionFactory + screen track builder
   ├─ webrtc/RtcSession         one PeerConnection per session (either role)
   ├─ session/SessionManager    owns the active session
   ├─ host/ScreenCaptureService attended FGS capture
   ├─ host/UnattendedHostService persistent unattended host
   ├─ host/SignalingService     short-lived FGS for FCM wake
   ├─ host/RemoteInputService   AccessibilityService input injection
   └─ fcm/RemoteFcmService      data-only wake push receiver
```

### Session lifecycle (single active session)

```
IDLE ──host-open / join──► CONNECTING ──consent + MediaProjection──► NEGOTIATING
   ──offer/answer + ICE──► CONNECTED ──ICE restart on network change──► RECOVERING
   ──► CONNECTED ──hangup / link closed──► IDLE
Failures (timeout, declined, ICE exhausted) return to IDLE with a user message.
```

`SessionManager` rejects duplicate sessions by tearing down the previous
`RtcSession` before starting a new one; `endNow()` closes the PeerConnection,
cancels recovery jobs, unregisters the network callback, and clears state so a
reconnect never duplicates the session.

---

## 3. Media transport (WebRTC)

- **Video:** host adds its screen `VideoTrack` (from `ScreenCapturerAndroid`)
  to a PeerConnection; the controller receives it RECV_ONLY. Hardware encoding
  is enabled (`DefaultVideoEncoderFactory(..., true, true)`), which prefers
  H.264 where the device supports it and falls back to VP8.
- **Control:** an ordered `DataChannel` named `"control"` carries JSON control
  messages from controller → host (see `docs/REMOTE_PROTOCOL.md`).
- **Security:** DTLS-SRTP end-to-end. Each peer signs its SDP's DTLS
  fingerprint with its identity key; the other peer verifies against the paired
  public key, so a compromised signaling server cannot MITM a paired session.
- **NAT:** ICE with STUN candidates from the server-provided list; TURN relay
  used only when P2P fails.

### Reconnection (ICE restart)

`RtcSession` arms recovery the moment the first connection succeeds:

- Registers a default-network callback → ICE restart on Wi-Fi ↔ cellular switch.
- On `DISCONNECTED` (2 s grace) or `FAILED` (immediate): reconnect signaling,
  refresh TURN credentials, `createOffer(IceRestart=true)` on the SAME
  PeerConnection (video + data channel survive — no black screen, no
  re-consent).
- Exponential backoff capped at 15 s, 6 attempts, then `CLOSED` → UI offers
  manual retry.

---

## 4. Signaling protocol (server ↔ client)

All messages are JSON over WebSocket. Full reference:
`docs/REMOTE_PROTOCOL.md`.

Key flows:

```
Host taps "Share my screen"
  host ──host-open──► server ──session-code(6-digit)──► host (+QR)

Controller enters/scans code
  ctrl ──join{code}──► server ──join-request──► host
  host: consent dialog → MediaProjection dialog → ScreenCaptureService
  host ──consent{accepted}──► server ──consent──► ctrl

WebRTC handshake relay (bidirectional, from-stamped)
  offer / answer / ice / restart / hangup

Offline host (unattended wake)
  ctrl ──join{hostId}──► server (host offline)
    ──FCM data-only wake──► host reconnects + re-registers
    server flushes queued join-request ──► UnattendedHostService
```

### Authenticated registration

`register` includes the device's DER public key; the server verifies
`deviceId == SHA-256(pubkey)`, issues a random nonce challenge, and only
registers the socket after the client signs the nonce. This prevents identity
hijacking: a client cannot claim a `deviceId` it doesn't own.

---

## 5. Authentication & pairing

- **Device identity:** per-install P-256 keypair in Android Keystore
  (`DeviceIdentity`). Private key never leaves the secure element; the DER
  public key and its hash (the `deviceId`) identify the device.
- **Pairing (QR):** `PairingManager` exchanges ephemeral ECDH keys, both sides
  prove identity ownership with ECDSA signatures over the transcript, derive a
  shared secret, and display an identical **safety number** that users compare
  out-of-band. On confirmation the pairing is persisted (encrypted) and
  registered with the server.
- **Per-session auth:** a dialer must be a trusted paired identity; the host
  verifies the DTLS fingerprint signature against the stored public key.
- **Unattended grants:** owner-created, scoped, expiring, revocable grants
  referencing a paired identity (never a raw id).

---

## 6. Data flow (end to end)

```
Controller sees remote screen (receive path)
  WebRTC video track ──► SurfaceViewRenderer (EGL) ──► screen

Controller input (send path)
  touch/gesture ──► normalized (0..1) JSON ──► DataChannel ──► InputRouter
  ──► AccessibilityService.dispatchGesture / performGlobalAction / typeText
```

Normalized coordinates mean different screen resolutions and aspect ratios map
correctly: the controller maps touches into the fitted video rect, sends 0..1
fractions, and the host multiplies by its own screen size. Orientation is
handled by the renderer (rotation metadata) and the fitted-rect math.

---

## 7. Server architecture

`server/` (Node.js, `ws`):

- **state.js** — in-memory maps (devices, sessions, pending wakes, grants,
  pairings, FCM tokens, join-rate windows). The module interface is small
  enough to back with Redis for horizontal scale.
- **server.js** — HTTP server hosting the WebSocket upgrade + `/healthz`,
  message router, ping/pong liveness, rate limiting, graceful shutdown.
- **turn.js** — coturn-compatible short-lived TURN credentials
  (`username=<expiry>:<label>`, password = HMAC-SHA1(secret, username)).
- **fcm.js** — optional Firebase wake (data-only, high priority).
- **log.js** — structured JSON logging (never secrets).

The server never sees media; it only brokers connection setup.

---

## 8. Infrastructure

- **Signaling:** any Node host; systemd unit provided in `infra/README.md`;
  TLS terminated by reverse proxy (or direct `wss`).
- **TURN:** coturn (`infra/turnserver.conf`) with `use-auth-secret`, denied
  internal ranges, no admin CLI, TLS.
- See `infra/README.md` for firewall, TLS, and monitoring details.
