# Remot — Technical Audit

**Date:** 2026-08-29
**Source:** `Vivek202509/remoteassist` (head `0012ba0`)
**Scope:** Full repository audit performed before the Remot rebrand, per the
audit-first execution model. This document describes what the project actually
is today (evidence-based), what works, what is missing, and what the Remot
build changes.

---

## 1. Executive summary

The source repository is a complete, coherent Android-to-Android remote-support
scaffold, not a toy. The core architecture is sound and already implements most
of the hard parts:

- WebRTC (community prebuilt SDK) for media + control data channel.
- A Node.js signaling broker (WebSocket) that never sees media.
- coturn STUN/TURN configuration with time-limited HMAC credentials.
- Hardware-backed P-256 device identity, authenticated ECDH pairing, encrypted
  trust store, per-session DTLS-fingerprint signing (MITM protection).
- MediaProjection screen capture, AccessibilityService input injection, FGS +
  persistent notifications.
- Attended (6-digit code) and unattended (standing grants + FCM wake) access.
- ICE-restart based reconnection with network-change watchdogs.
- A working protocol smoke test suite (12/12) that runs with no Android device.

The main gaps for "production-quality":

1. **Signaling registration is unauthenticated** — any client can claim any
   `deviceId` and hijack message routing. The single most serious security hole.
2. **minSdk is 26 (Android 8.0)** — the product target is Android 7.1 (API 25);
   only `NotificationChannel` actually blocks 25, and it is trivially guardable.
3. **Branding is inconsistent** (`RemoteAssist` everywhere, package
   `com.remoteassist`, deep link `remoteassist://`).
4. **Server robustness** — no graceful shutdown, no health endpoint, no rate
   limiting, ad-hoc logging, no structured logs.
5. **Documentation is stale** — the README claims the QR scanner and remote
   video surface are unimplemented "integration points"; both are fully
   implemented in code.
6. **Versioning is ad-hoc** (`1.0`, no changelog).

Nothing in the audit warrants a rewrite: the fixes are targeted.

---

## 2. Architecture (as-found)

### 2.1 Android application

- **Language/UI:** Kotlin, Jetpack Compose (Material 3), single-activity
  (`MainActivity` → `AppRoot` → per-`Screen` composables).
- **DI:** manual singleton registry `ServiceLocator` implementing the signaling
  `Listener`; UI callbacks (lambdas) set by `MainViewModel`.
- **minSdk 26 / targetSdk 35 / compileSdk 35**, JDK 17, AGP 8.6.1, Kotlin 2.0.20.
- **Package layout (`com.remoteassist`):**
  - `identity/DeviceIdentity` — Android Keystore P-256 keypair; the DER public
    key *is* the device identity; `deviceId` = SHA-256 of the pubkey, hex.
  - `crypto/Crypto` — ECDSA verify, ephemeral ECDH, safety-number derivation,
    base64/hex helpers.
  - `trust/` — `TrustStore` (EncryptedSharedPreferences) of paired peers;
    `PairingManager` drives the QR pairing handshake (offer → pair-complete →
    pair-ack → out-of-band safety number → `register-pairing`).
  - `unattended/GrantStore` — standing, scoped, expiring, revocable grants
    referencing a paired identity.
  - `signaling/SignalingClient` — OkHttp WebSocket, auto-reconnect with
    exponential backoff, re-register on open.
  - `webrtc/` — `WebRtcCore` (factory/EGL + `buildScreenTrack` via
    `ScreenCapturerAndroid`) and `RtcSession` (offer/answer/ICE, control data
    channel, fingerprint signing, ICE-restart recovery, default-network
    callback watchdog, 6-attempt capped backoff).
  - `session/SessionManager` — owns the single active `RtcSession` for either
    role; routes control messages into `InputRouter`.
  - `host/` — `ScreenCaptureService` (attended, FGS `mediaProjection`),
    `UnattendedHostService` (persistent, holds projection), `SignalingService`
    (short-lived FGS on FCM wake), `RemoteInputService` (AccessibilityService),
    `Notifications`.
  - `fcm/RemoteFcmService` — data-only high-priority wake push → reconnects
    signaling → routes to grant auto-accept or attended consent notification.
  - `ui/` — Compose screens: HOME, HOST_CODE (QR + 6-digit code), JOIN, SCAN
    (CameraX + ML Kit barcode), PAIRED, SAFETY_NUMBER, SESSION (renders remote
    video via `RemoteVideoSurface`, maps touches to normalized taps/swipes).

### 2.2 Signaling server (`server/`, Node.js + `ws`)

- **Role:** device registration, code/paired-direct join brokering, WebRTC
  handshake relay, pairing/auth relay, TURN credential issuance, optional FCM
  wake. **Never touches media.**
- **State:** in-memory maps (`devices`, `sessions`, `pendingWakes`, `grants`,
  `pairings`, `fcmTokens`) in `state.js`; comment documents Redis as the
  scale-out path.
- **Protocol:** JSON messages over WebSocket (`register`, `host-open`,
  `join`, `consent`, `offer`, `answer`, `ice`, `restart`, `hangup`,
  `register-grant`, `revoke-grant`, `register-pairing`, `revoke-pairing`,
  `pair-complete`, `pair-ack`, `auth-challenge`, `auth-response`,
  `report-token`, `turn-credentials`).
- **TURN:** `turn.js` issues coturn-compatible short-lived credentials
  (username `<expiry>:<label>`, password HMAC-SHA1 of secret). `iceServers()`
  returns STUN + TURN UDP + TURNS TCP entries.
- **FCM:** optional `firebase-admin`; disabled by default; no-op when absent.
- **Tests:** `test/smoke.js` — 12 protocol checks, no Android needed, 12/12
  passing.

### 2.3 Infrastructure (`infra/`)

- `turnserver.conf` — hardened coturn config (no-cli, denied internal ranges,
  TLS via Let's Encrypt, static-auth-secret). Requires editing `external-ip`,
  realm, secret, cert paths before deploy.

### 2.4 CI (`.github/workflows/ci.yml`)

- Server smoke test job (Node 20) + Android job (JDK 17, SDK 35, build-tools
  34.0.0, `assembleDebug testDebugUnitTest lintDebug`, APK artifact).
- Triggered on push/PR to main with concurrency cancellation. No release
  pipeline, no tag trigger, no release APK, no GitHub Release creation.

---

## 3. Data flow (as-found)

```
Attended session (code path)
────────────────────────────
Host taps "Share my screen" ──host-open──► server ──► 6-digit code (+QR) shown
Controller enters/scans code ──join──► server ──join-request──► Host
Host: consent dialog (Allow) ──► MediaProjection system dialog (Start now)
      ScreenCaptureService (FGS + persistent notification) builds screen track
Host ──offer(+fingerprint sig)──► server ──► Controller ──answer(+sig)──► Host
      ICE via STUN/coturn ──► P2P (or relayed)
Controller sees screen; taps/swipes/keys ──DataChannel──► Host AccessibilityService
Either side ends ──► hangup, PeerConnection closes, FGS stops, notification clears

Unattended session (paired-direct path)
───────────────────────────────────────
Controller dials paired host id ──join──► server
  host offline? ──► FCM data-only wake ──► SignalingService reconnects ──►
  server flushes queued join-request ──► UnattendedHostService checks grant
    grant valid  ──► auto-start host (no dialogs), scope applied
    no grant     ──► attended consent notification
```

---

## 4. Capability matrix

| Capability                | Current                 | Required | Status          |
| ------------------------- | ----------------------- | -------- | --------------- |
| Device pairing (QR/ECDH)  | Implemented             | Yes      | ✅ Present      |
| Device discovery          | 6-digit code + paired   | Yes      | ✅ Present      |
| Authentication (signaling)| **Missing** — `register` is unauthenticated; pairing/auth is peer-level | Yes | 🔴 Fix (this audit) |
| Screen streaming          | MediaProjection + WebRTC video track | Yes | ✅ Present (device-tested needed) |
| Remote touch (tap)        | `dispatchGesture` via AccessibilityService | Yes | ✅ Present |
| Swipe                     | Implemented (normalized coords) | Yes | ✅ Present |
| Long press                | **Missing**             | Yes      | ➕ Add          |
| Back/Home/Recent          | `performGlobalAction`   | Yes      | ✅ Present |
| Keyboard input            | `typeText` (protocol `text`) — **no UI to send text** | Yes | ➕ Add controller UI |
| Multi-touch               | Not implemented         | Optional | 📝 Limitation (single-stroke dispatch) |
| Clipboard                 | Not implemented         | Optional | 📝 Not in scope (P2) |
| File transfer             | Not implemented         | Optional | 📝 Not in scope (P2) |
| Audio                     | Not implemented (permission commented out) | Optional | 📝 Not in scope (P2) |
| Camera                    | QR scan camera only     | Optional | ✅ Present |
| Multi-device              | Single active session   | Optional | 📝 Single-session by design |
| NAT traversal             | STUN via `iceServers`   | Yes      | ✅ Present |
| TURN fallback             | coturn config + creds   | Yes      | ✅ Present (deploy needed) |
| Session reconnect         | ICE restart + signaling reconnect, 6 attempts | Yes | ✅ Present |
| Encryption                | DTLS-SRTP + fingerprint-signed SDP + TLS/WSS | Yes | ✅ Present |
| Battery optimization      | Minimal polling, 20s ping, bounded wakelock | Yes | ✅ Reviewed (see §8) |
| Android 7.1 (API 25)      | minSdk 26 — blocked by `NotificationChannel` only | Yes | ➕ Lower to 25 |
| Android 16 (API 36)       | Runs on 16 (targetSdk 35); 16 KB page-size caveat for WebRTC `.so` | Yes | 📝 Reviewed, see §8 |
| Remote video surface      | Implemented + wired in SESSION screen (README is stale) | Yes | ✅ Present |
| QR scanner                | Implemented (CameraX + ML Kit) (README is stale) | Yes | ✅ Present |

---

## 5. Security review (as-found)

**Implemented and sound**

- Hardware-backed P-256 identity in Android Keystore; private key never
  exportable.
- Pairing is authenticated ECDH with mutual identity proofs and an out-of-band
  safety number; trust persisted in EncryptedSharedPreferences.
- Per-session media MITM protection: each peer signs its DTLS fingerprint with
  its identity key; the other peer verifies against the paired public key. A
  compromised signaling server cannot insert itself into a paired session.
- Short-lived 6-digit codes (5 min TTL, one-time use) + explicit host consent.
- Time-limited TURN credentials (HMAC expiry, 1 h TTL).
- Unattended grants are scoped, expiring, revocable, and reference a paired
  identity.
- No secrets in the repository; `.env` ignored; FCM optional.

**Findings**

| # | Severity | Finding | Fix |
|---|----------|---------|-----|
| S1 | **High** | `register` sets `ws.deviceId` from client-supplied data with no proof of key ownership. Any client can claim a victim's `deviceId`, hijack inbound join-requests/handshake messages, or evict the real device from the `devices` map. | Signed registration: client sends `pubKeyB64`; server verifies `deviceId == sha256(pubKey)`, issues a nonce challenge, verifies an ECDSA signature, and only then registers the connection. |
| S2 | Medium | No rate limiting; an attacker can spam `join`/`host-open` and exhaust the code space or memory. | Per-connection message throttle + per-IP join attempt limit. |
| S3 | Low | No graceful shutdown; in-flight sessions are cut unceremoniously on deploy. | SIGINT/SIGTERM handler closing sockets with 1001 and stopping timers. |
| S4 | Low | Logging is ad-hoc `console.log` with no structure. | Structured JSON log lines (no secrets). |
| S5 | Low | `SYSTEM_ALERT_WINDOW` is declared in the manifest but nothing uses it — a Play-policy risk ("unused sensitive permission"). | Remove the declaration (or implement the overlay; out of scope). |
| S6 | Info | Server state is in-memory only; a restart drops all pairings/grants (clients re-register pairings on next connect, but grants must be re-registered). | Documented; Redis-backed state is the documented scale-out path. Out of scope for v1.0.0. |
| S7 | Info | `USE_FULL_SCREEN_INTENT` on Android 14+ requires user opt-in in Settings for non-calling apps; app already degrades to heads-up. | Documented in compatibility notes. |

---

## 6. Android platform reality (as-found)

- Screen capture: MediaProjection inside a `mediaProjection` foreground
  service; mandatory persistent notification; per-session system consent.
- Input injection: AccessibilityService `dispatchGesture` + `performGlobalAction`
  + `ACTION_SET_TEXT`. No root, no hidden APIs, no accessibility abuse — the
  service only acts during an approved session.
- Unattended access: Android 11+ MediaProjection permission Intents are
  effectively single-use; the scaffold stores the grant Intent. The documented
  platform ceiling is that a production unattended build must keep the
  capturer/track alive across sessions. This is unchanged by the Remot build.
- Android 13+: `POST_NOTIFICATIONS` runtime permission requested on Home.
- Android 14+: FGS type requirements and start-from-background restrictions are
  handled (FGS types declared, FCM high-priority exempts wake path).
- Android 16: app targets SDK 35 so runs on 16; 16 KB page-size devices require
  16 KB-aligned native libs — depends on the WebRTC prebuilt and cannot be
  verified without hardware.

---

## 7. Testing status (as-found)

- `server`: `npm run smoke` → **12/12 pass** (verified during audit).
- `android`: unit tests exist for `SessionCodes` only. Build/test/lint require
  an SDK + JDK 17 and run in CI (`ci.yml`). No emulator/device tests possible
  in this environment.
- No UI tests, no server integration tests beyond the smoke suite, no
  end-to-end two-device test harness.

---

## 8. Battery / performance review (as-found)

- Signaling ping every 20 s (OkHttp `pingInterval`) — cheap, keeps half-open
  sockets detected.
- ICE restart instead of full session rebuild on network change — avoids
  re-consent and re-capture.
- Bounded partial wakelock (2 h cap) on the unattended host.
- Frame pipeline: `ScreenCapturerAndroid` → `VideoSource` → hardware encoder
  (`DefaultVideoEncoderFactory` with hardware paths enabled) → network. No
  intermediate Bitmap copies; renderer uses `setEnableHardwareScaler(true)`.
- No explicit bitrate/FPS adaptation loop — WebRTC's built-in congestion
  control (`googCpuOveruseDetection`, REMB/TWCC) applies, but no app-level
  FPS/resolution fallback is implemented. Documented as a P1 follow-up.

---

## 9. What the Remot build changes (planned)

| Area | Change |
|------|--------|
| Branding | Full rename to **Remot**: package `com.remoteassist` → `com.remot.app`, deep link `remot://`, theme `Theme.Remot`, keystore alias, wakelock tag, notification copy, README/docs, server package name, play-console text, demo artifacts. |
| Security | Signed registration (S1), rate limiting (S2), graceful shutdown (S3), structured logging (S4), remove unused `SYSTEM_ALERT_WINDOW` (S5). |
| Compatibility | minSdk 26 → 25 (Android 7.1) with `NotificationChannel` guards; document Android 16. |
| Input | Add long-press protocol action + controller UI; add keyboard/text input UI on controller; aspect-correct touch mapping on the video surface. |
| Docs | New `README.md`, `docs/AUDIT.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, `docs/ANDROID_COMPATIBILITY.md`, `docs/REMOTE_PROTOCOL.md`, `docs/DEVELOPMENT.md`, `infra/README.md`, root `.env.example`, `CHANGELOG.md`. |
| Versioning | `1.0.0` (versionCode 1). |
| CI | `android-release.yml`: lightweight push/PR validation, tag-triggered release APK + GitHub Release. |
