# Remot

**Android-to-Android remote control over the Internet.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Remot lets one Android device securely view and control another Android device
— across the room or across the world. It is a consent-first remote-support
tool (comparable to AnyDesk / TeamViewer QuickSupport) built entirely on public
Android APIs: **MediaProjection** for screen capture, **AccessibilityService**
for input injection, and **WebRTC** (DTLS-SRTP) for end-to-end-encrypted
transport.

- **No root. No hidden APIs.** Remote control uses only legitimate platform APIs.
- **Consent-first:** the controlled device always approves a session, and a
  persistent notification is shown the whole time access is active.
- **Peer-to-peer media:** the signaling server only brokers connection setup —
  it never sees your screen.
- **Android 7.1 → Android 16** (see `docs/ANDROID_COMPATIBILITY.md`).

---

## Features

| | |
|---|---|
| Screen sharing | Live MediaProjection capture streamed over WebRTC (H.264/VP8, hardware encoding) |
| Remote control | Tap, long-press, swipe/drag, Back/Home/Recents, text input via AccessibilityService |
| Pairing | QR + authenticated ECDH pairing with out-of-band safety-number verification |
| Unattended access | Scoped, expiring, revocable grants for trusted paired devices (optional) |
| NAT traversal | STUN + coturn TURN with time-limited credentials; relay only when P2P fails |
| Reconnection | ICE restart on network change; no black screen, no re-consent |
| Security | Hardware-backed P-256 identity, signed registration, DTLS-fingerprint MITM protection, encrypted trust store |
| Device wake | Optional FCM high-priority push wakes an offline host for an incoming session |

## Requirements

- **Controller + host:** two Android devices (or two emulators with network
  setup), Android 7.1+.
- **Signaling server:** Node.js ≥ 18, reachable by both devices (`wss://` in
  production).
- **TURN (cross-network):** coturn on a host with a public IP (see
  `infra/README.md`). Two phones on different networks almost always need it
  (carrier CGNAT blocks P2P).

---

## Quick start

### 1. Run the signaling server

```bash
cd server
npm ci
npm run smoke      # 12+ protocol checks, no Android needed
npm start          # ws://0.0.0.0:8080
```

Configure via environment (`server/.env.example`). FCM is optional and off by
default; attended sessions need no wake.

### 2. Build the Android app

```bash
cd android
./gradlew :app:assembleDebug
```

Debug builds connect to `ws://10.0.2.2:8080` (emulator → host loopback). For
two real devices, point `SIGNALING_URL` (in `app/build.gradle.kts`) at your
machine's LAN IP or a deployed `wss://` URL.

### 3. Pair and connect

1. **Host:** tap **Share my screen** → approve the consent dialog and the
   Android screen-capture dialog. A 6-digit code (+ QR) appears.
2. **Controller:** tap **Connect**, enter the code (or scan the QR).
3. The host approves; the controller sees the live screen and can tap, swipe,
   long-press, press Back/Home/Recents, and type text.
4. Either side taps **End session**; the notification clears and the
   connection closes.

For **unattended access**, set up a standing grant on the host for a paired
device (requires pairing first). See `docs/ARCHITECTURE.md`.

---

## Firewall ports (deployed server)

The production signaling server + coturn run on the same host (floating IP
`43.156.82.52`). Open these ports in the cloud security group / firewall so
Remot works across networks:

| Port(s) | Protocol | Purpose | Needed for |
| --- | --- | --- | --- |
| `8080` | TCP | Signaling WebSocket — `ws://43.156.82.52:8080` (registration, pairing, session codes, handshake relay) | The app connecting to the server |
| `3478` | UDP | STUN + TURN (listener) | NAT traversal / P2P discovery + relay |
| `3478` | TCP | TURN over TCP (fallback when UDP is blocked) | Relay fallback |
| `49152–65535` | UDP | TURN relay allocations (media relay) | Actual media relay when P2P fails |
| `5349` | TCP | TURNS (TURN over TLS) | Only if TLS + a domain are configured (not yet deployed) |

> **Note:** TCP `8080` is already reachable. UDP `3478` and the relay range are
> currently **filtered** — until they are opened, direct P2P and TURN relay
> across the public Internet will not work (sessions still connect over LAN).
> After opening them, re-verify with the trickle-ICE test described in
> `infra/README.md`.

---

## How a session flows

```
Host taps "Share my screen" ──host-open──► server ──► 6-digit code shown
Controller enters code ──join──► server ──join-request──► Host
Host: consent dialog → MediaProjection dialog → ScreenCaptureService (FGS)
Host ──offer(+signed fingerprint)──► server ──► Controller ──answer──► Host
      ICE via STUN/coturn ──► P2P (or relayed)
Controller sees screen; gestures/keys ──DataChannel──► Host AccessibilityService
Either side ends ──► hangup, PeerConnection closes, FGS stops
```

Bidirectional: both roles ship in one APK; whichever device runs **Share my
screen** is the controlled one for that session.

---

## Repository layout

```
├── android/                  # Kotlin / Jetpack Compose app
│   └── app/src/main/java/com/remot/app/
│       ├── RemoteApp.kt          # Application: init + FCM token
│       ├── ServiceLocator.kt     # manual DI + signaling listener fan-out
│       ├── MainActivity.kt       # Compose host + permission launchers
│       ├── MainViewModel.kt      # UI state + intents
│       ├── crypto/ identity/ trust/ unattended/
│       ├── signaling/ webrtc/ session/
│       ├── host/ fcm/            # capture, input, wake services
│       └── ui/                   # Compose screens
├── server/                   # Node.js signaling + pairing broker + FCM wake
│   ├── src/server.js         # WebSocket message router + health + rate limits
│   ├── src/state.js          # in-memory broker state
│   ├── src/turn.js           # time-limited TURN credentials
│   ├── src/fcm.js            # optional Firebase wake
│   └── test/smoke.js         # end-to-end protocol test (no Android needed)
├── infra/
│   ├── turnserver.conf       # coturn config (STUN/TURN)
│   └── README.md             # deployment: server, coturn, firewall, TLS
├── docs/                     # AUDIT, ARCHITECTURE, SECURITY, COMPATIBILITY,
│                             # REMOTE_PROTOCOL, DEVELOPMENT
└── CHANGELOG.md
```

## Documentation

| Document | Contents |
| --- | --- |
| `docs/AUDIT.md` | Technical audit of the source project + capability matrix |
| `docs/ARCHITECTURE.md` | System, Android, server, transport, data flow |
| `docs/SECURITY.md` | Security model, threats, mitigations |
| `docs/ANDROID_COMPATIBILITY.md` | Android 7.1–16 support + platform limitations |
| `docs/REMOTE_PROTOCOL.md` | Signaling + control protocol reference |
| `docs/DEVELOPMENT.md` | Build, test, release, CI workflow |
| `infra/README.md` | Signaling + TURN deployment, firewall, TLS |

## Security model (summary)

- **Identity:** per-install P-256 keypair in Android Keystore; the public key
  IS the device ID.
- **Signed registration:** the signaling server only registers a device after
  verifying `deviceId == SHA-256(pubkey)` and a signature over a fresh
  challenge — identity hijacking is not possible.
- **Pairing:** authenticated ECDH + mutual identity proofs + an out-of-band
  safety number users compare.
- **Media:** DTLS-SRTP end to end; peers sign their DTLS fingerprints with
  their identity keys, so a compromised signaling server cannot MITM a paired
  session.
- **Sessions:** one-time 6-digit codes (5 min TTL) + explicit host consent;
  paired dials require an existing trusted pairing.
- **Unattended grants:** scoped, expiring, revocable, referencing a paired
  identity only.
- No secrets in the repo; `.env` git-ignored. Full model: `docs/SECURITY.md`.

## Known Android limitations

- Unattended access on Android 11+ is limited by single-use MediaProjection
  intents (documented in `docs/ANDROID_COMPATIBILITY.md`).
- Secure screens (DRM, some banking apps) cannot be captured by MediaProjection.
- Multi-touch and device audio are not implemented in v1.0.0.
- Device wake requires a Firebase project (FCM); attended sessions work
  without it.

## License

MIT — see [LICENSE](LICENSE).
