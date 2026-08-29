# Changelog

All notable changes to Remot are documented here.

The format follows the project convention: every version entry contains the
version, date, short summary, changed components, bug fixes, and notable
technical changes.

Production versions use the V/C/P scheme (see `docs/VERSIONING.md`):
`V1C001`, `V1C001P01`, ... Legacy semver releases are preserved below under
`Legacy` entries. New versions are always inserted at the top.

---

## V1C001 — 2026-08-29

### Summary

First production version under the V/C/P versioning scheme. Continues the
same codebase as the legacy `v1.0.0`/`v1.0.1` releases: Remot is a
consent-first, end-to-end-encrypted Android-to-Android remote control
application (rebranded from RemoteAssist, `com.remot.app`, Android 7.1+),
with an authenticated signaling broker, WebRTC P2P media/control, and a
two-workflow GitHub Actions pipeline.

### Added

- V/C/P production versioning: `docs/VERSIONING.md` defining the
  `V{major}C{change}P{patch}` scheme, the `versionCode = V*100000 + C*100 + P`
  mapping (V1C001 → 100100), lowercase tag format, and the semver migration.
- `scripts/release-check.sh` — release gate validating git state, secrets,
  version format, CHANGELOG, Android `versionName`/`versionCode`, unit tests,
  lint, release build, and APK metadata before a production tag is created.
- Android `versionName` `V1C001`, `versionCode` `100100` (was 1.0.0 / 1).

### Changed

- Rebranded RemoteAssist → Remot across app, server, docs, and CI; package
  `com.remoteassist` → `com.remot.app`; deep link `remot://`; `minSdk` 25
  (Android 7.1) with `NotificationChannel` guards.
- Home/session UI redesigned; long-press control, keyboard text input, and
  aspect-correct touch mapping added on the controller.
- Android `SIGNALING_URL` (debug + release) now points at the production
  signaling broker (`ws://` until TLS is deployed); the broker issues
  STUN/TURN ice servers against the public TURN hostname `turn.robrion.net`.
- Signaling registration is now authenticated (server verifies
  `deviceId == SHA-256(pubKey)` + ECDSA signature over a fresh nonce).

### Fixed

- Server previously accepted any claimed `deviceId` without proof of key
  ownership (identity/routing hijacking) — now rejected.
- GitHub Release creation 403 (`Resource not accessible by integration`) —
  release workflow declares `permissions: contents: write` and passes the
  built-in `github.token`. No PAT is used.
- Release pipeline no longer runs on ordinary `main` pushes; duplicate
  workflow execution eliminated by splitting into `ci.yml` (PR + main) and
  `release.yml` (production tags only).
- Removed unused `SYSTEM_ALERT_WINDOW` permission (Play-policy risk).

### Security

- Signed device registration; forged pairings rejected; rate limiting for
  joins and per-socket message floods; time-limited TURN credentials;
  DTLS-fingerprint MITM protection; `.env` git-ignored, no secrets in repo.

### CI

- `ci.yml` (Remot CI): pull requests + pushes to `main` — server smoke test,
  Android build/unit tests/lint, debug APK artifact. Never creates releases.
- `release.yml` (Remot Release): triggers ONLY on V/C/P production tags
  (`v[0-9]c[0-9][0-9][0-9]` / `v[0-9]c[0-9][0-9][0-9]p[0-9][0-9]`) — builds
  only `assembleRelease`, verifies APK metadata, names it
  `remot-v<tag>.apk`, creates GitHub Release `Remot V<version>` with the APK
  attached. Runs once per tag; optional signing via `KEYSTORE_*` secrets.
- Versioning migration: legacy tags `v1.0.0` / `v1.0.1` are preserved; the
  first V/C/P production tag is `v1c001`.

### Notable technical changes

- Protocol: registration handshake extended (`auth-challenge`/`auth-response`
  without `to` = server-direct; with `to` = peer relay) — documented in
  `docs/REMOTE_PROTOCOL.md`.
- Server: WebSocket server hosted on an HTTP server to serve `/healthz`;
  graceful shutdown on SIGINT/SIGTERM; structured JSON logging; rejected
  registrations close the connection (1008/4000).
- Android: `SignalingClient` presents `DeviceIdentity.publicKeyB64()` on
  register and stops auto-reconnecting after a permanent registration
  rejection; `long-press` maps to a 600 ms gesture stroke on the host.

---

## Legacy v1.0.1 — 2026-08-29

### Summary

Legacy semver release under the old scheme (superseded by V1C001).
Restructured GitHub Actions to eliminate duplicate runs and guarantee release
permissions: CI and release are now separate workflows.

### Fixed

- GitHub Release creation previously failed with HTTP 403
  (`Resource not accessible by integration`) — the release workflow now
  declares `permissions: contents: write` at the workflow level and passes the
  built-in `github.token` to the release action. No PAT is used.
- Release pipeline no longer runs on ordinary `main` pushes (previously one
  workflow matched both `main` and tags, so a branch+tag push executed it
  twice).
- Reduced duplicate GitHub Actions execution by splitting the single workflow
  into `ci.yml` and `release.yml`.

### CI

- `ci.yml` (Remot CI) runs only on pull requests and pushes to `main`: server
  smoke test + Android build/unit tests/lint + debug APK artifact. It never
  creates releases. Superseded runs are cancelled (`ci-${{ github.ref }}`).
- `release.yml` (Remot Release) triggers ONLY on semantic version tags
  (`v*.*.*`): builds the release APK, names it `remot-v<version>.apk`, and
  creates a GitHub Release with the APK attached. Runs once per tag
  (`release-${{ github.ref }}`, no cancellation).
- Release workflow builds only `assembleRelease` (the redundant debug build
  was removed). Optional signing via `KEYSTORE_*` secrets is preserved;
  without them the release APK is unsigned.
- Release APK naming is tag-driven: tag `v1.0.1` produces `remot-v1.0.1.apk`.

---

## Legacy v1.0.0 — 2026-08-29

### Summary

Initial production release of Remot: a full rebrand and hardening of the
RemoteAssist remote-support scaffold into a consent-first, end-to-end-encrypted
Android-to-Android remote control application. Rebranded from RemoteAssist to
Remot, moved to `com.remot.app`, lowered the minimum supported Android version
to 7.1, authenticated device registration on the signaling server, and added
the full documentation and release pipeline.

### Added

- Initial Remot project baseline (versionCode 1, versionName 1.0.0).
- `CHANGELOG.md`, `docs/AUDIT.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY.md`,
  `docs/ANDROID_COMPATIBILITY.md`, `docs/REMOTE_PROTOCOL.md`,
  `docs/DEVELOPMENT.md`, `infra/README.md`, and root `.env.example`.
- Long-press control (`long-press` protocol action + controller gesture).
- Keyboard text input UI on the controller (sends the existing `text` action).
- Aspect-correct touch mapping on the remote video surface (touches map into
  the fitted video rect, ignoring letterbox bars; orientation-aware).
- Server: `/healthz` endpoint, graceful shutdown on SIGINT/SIGTERM,
  per-connection message rate limiting, per-IP join rate limiting, structured
  JSON logging.
- CI: `android-release.yml` with tag-triggered GitHub Release and APK artifacts.

### Changed

- Rebranded RemoteAssist → Remot across app, server, docs, and CI.
- Android package migrated `com.remoteassist` → `com.remot.app` (namespace,
  applicationId, source tree); deep link scheme `remoteassist://` → `remot://`;
  theme `Theme.RemoteAssist` → `Theme.Remot`; Keystore alias and wakelock tag
  renamed accordingly.
- `minSdk` lowered 26 → 25 (Android 7.1) with runtime guards for
  `NotificationChannel`.
- Home screen redesigned with Remot branding and this-device identity card;
  session screen gets an icon toolbar and keyboard input.
- Signaling registration is now authenticated (see Security).
- Turn label and server package renamed to `remot` / `remot-signaling`.
- README rewritten; stale "integration point" claims about the QR scanner and
  remote video surface removed (both are implemented).

### Fixed

- Server could previously accept any claimed `deviceId` without proof of key
  ownership, allowing identity/routing hijacking — now rejected (see Security).
- Test harness no longer treats relayed peer auth-challenges as registration
  challenges (server correctly rejects misbehaving clients).
- Removed unused `SYSTEM_ALERT_WINDOW` permission (Play-policy risk).

### Security

- Signed device registration: server verifies `deviceId == SHA-256(pubKey)` and
  an ECDSA signature over a fresh nonce before routing to a socket.
- `register-pairing`/`revoke-pairing` only accept the sender's own authenticated
  deviceId (no forging pairings for other devices).
- Rate limiting for joins (brute-force/code-guessing protection) and per-socket
  message floods.
- `.env` ignored; `.env.example` carries placeholders only; no secrets in repo.

### Notable technical changes

- Protocol: registration handshake extended (`auth-challenge`/`auth-response`
  without `to` = server-direct; with `to` = peer relay) — documented in
  `docs/REMOTE_PROTOCOL.md`.
- Server: WebSocket server now hosted on an HTTP server to serve `/healthz`;
  rejected registrations close the connection (1008/4000).
- Android: `SignalingClient` presents `DeviceIdentity.publicKeyB64()` on
  register and stops auto-reconnecting after a permanent registration rejection.
- Input: `long-press` maps to a 600 ms gesture stroke on the host.
