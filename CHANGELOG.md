# Changelog

All notable changes to Remot are documented here.

The format follows the project convention: every version entry contains the
version, date, short summary, changed components, bug fixes, and notable
technical changes.

Production versions use the V/C/P scheme (see `docs/VERSIONING.md`):
`V1C001`, `V1C001P01`, ... Legacy semver releases are preserved below under
`Legacy` entries. New versions are always inserted at the top.

---

## V2C002 — 2026-08-29

### Summary

Android 16 compatibility, UI redesign, and real infrastructure health
monitoring. Fixes the Accessibility Service and Notification Listener so they
can actually be enabled on Android 16, makes the UI respect system window
insets (no more content under the status bar), redesigns the dashboard with a
modern design system, and adds honest STUN/TURN health checks with measured
latency plus WebRTC connection-route diagnostics.

### Fixed

- **Android 16 Accessibility Service:** the service was declared
  `android:exported="false"`, which prevents Android (Settings) from binding
  and enabling it — the merged manifest now declares it `exported="true"`
  with the BIND_ACCESSIBILITY_SERVICE permission (binding stays system-only)
  plus a proper label. Tightened the accessibility config to only the
  capabilities Remot actually uses (gestures + focused-text input); removed
  the resource-heavy `typeAllMask` / key-filter flags.
- **Notification Listener:** the app had NO NotificationListenerService at
  all — a status that claimed to be “cannot be enabled” was in fact “not
  implemented”. Added `RemotNotificationListener` (exported=true,
  BIND_NOTIFICATION_LISTENER_SERVICE) with lifecycle tracking; notifications
  are never read, logged, or transmitted.
- **System window insets:** the app now runs edge-to-edge (Android 15/16
  enforce this) and the Compose root applies `WindowInsets.safeDrawing`, so
  the header, content, and navigation never hide under the status bar,
  display cutout, or gesture/navigation bar.

### Added

- `ServiceStatus` — real system-state detection for Accessibility and
  Notification Access (INSTALLED / ENABLED / CONNECTED) read from the actual
  Settings / system bind state, never a cached boolean.
- **System Services screen** with per-service status and “Manage” buttons
  that open the correct Android settings screen (with safe fallbacks) and
  re-check state on return.
- `NetworkHealthRepository` — single source of truth for Internet, Signaling,
  STUN, and TURN health with one shared polling loop (15s, stopped in
  background, immediate on network change / resume / manual refresh).
- `StunTurnProbe` — real STUN Binding + TURN Allocate handshake (RFC 5389 /
  RFC 5766) using the short-lived credentials the signaling server issues;
  reports DNS / STUN / TURN separately and measures real round-trip latency
  in ms. Never fakes status from DNS or config alone.
- **WebRTC ICE route diagnostics** — the selected candidate type
  (host / srflx / relay) is read from the stats API, so the UI honestly shows
  whether the session is using a TURN relay or a direct path.
- Diagnostics section: Android version/SDK, manufacturer/model, app version,
  device ID, network health, latency, ICE route.

### Improved

- Redesigned dashboard: modern design system (tokens, typography, shapes),
  connection-health card, compact session state, primary actions prioritized,
  status shown as icon+text (never color alone).
- Consistent screens (Host code, Join, Paired, Safety number, Session)
  restyled to the same system; safe inset handling throughout.

### Validation

- Accessibility manifest (merged): exported=true + BIND_ACCESSIBILITY_SERVICE + intent-filter — PASS
- Notification listener manifest (merged): exported=true + BIND_NOTIFICATION_LISTENER_SERVICE + intent-filter — PASS
- STUN/TURN probe against local coturn with production credentials: STUN OK, TURN Allocate OK (measured ms) — PASS
- Debug build / unit tests / lint / signed release build / apksigner verify — PASS (local)
- **StunTurnProbe header parsing:** STUN responses were parsed with the magic
  cookie read 2 bytes too early (past the message-length field), so every
  Binding / Allocate response was rejected as a cookie/transaction mismatch and
  real STUN/TURN health checks could never report online. Fixed by skipping the
  header length field before the cookie; covered by the loopback unit tests.
- Added unit tests for `ServiceStatus` (Settings.Secure component-list membership
  + installed/enabled/connected resolver) and `StunTurnProbe` (loopback mock
  STUN/TURN: binding, 401 challenge, authenticated Allocate, timeout, DNS
  failure) — execution verified on the GitHub Actions Build run
- Real-device Android 16 enable + remote session: pending device testing

---

## V2C001 — 2026-08-29

### Summary

New V2 generation of the Remot production Android release under a fresh
package identity and persistent production signing. Migrates the Android
application ID to `com.robrion.remot`, signs the release APK with a stable,
secrets-backed production keystore, points STUN/TURN at the configured
production hostname (credentials still issued at runtime by the signaling
server — none are embedded in the APK), and replaces the CI/CD pipeline with
a strict build-then-release chain.

### Changed

- Android `applicationId` / `namespace` migrated `com.remot.app` →
  `com.robrion.remot` (source tree, package declarations, imports, manifest
  references). This is a new package identity; see
  `docs/VERSIONING.md` for the migration note.
- Release builds now produce a single universal signed APK
  (`app-release.apk`), not split/unsigned artifacts.
- `SIGNALING_URL` and all signing credentials are supplied at build time via
  Gradle properties / environment / GitHub Secrets; no endpoints or secrets
  are hardcoded in source.
- TURN/STUN now served under the configured production hostname, with
  credentials issued to clients at runtime by the signaling server
  (`TURN_HOST`, `TURN_SECRET` remain server-side).

### Added

- Persistent production signing: a dedicated keystore supplied via GitHub
  Secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) so future releases can upgrade
  previous installs without a signature mismatch.
- `build.yml` — Build workflow (tests, lint, lint validation, signed release
  build, APK identity/signature validation, artifact upload). Never releases.
- `release.yml` — Release workflow, triggered only by a successful Build
  (`workflow_run`), that downloads the exact artifact, re-validates it, and
  creates the GitHub Release. No parallel build at release time; failed
  builds never reach Release; one canonical release path; single global
  release concurrency slot.

### Security

- No TURN secret, coturn `static-auth-secret`, or server credentials are
  compiled into the APK; only the packaging identity and build-config
  endpoints are present.
- Release APK is signed with a persistent production key (never debug key,
  never unsigned); release builds fail hard if signing credentials are absent.
- README and public docs no longer expose the production TURN hostname,
  ports, or any infrastructure IP addresses.

### Fixed

- Release APK previously could build unsigned or be published as a split/
default artifact; it is now always a single installable, signed universal
  APK validated by `apksigner` before release.
- Build and Release previously shared/duplicated triggers; they are now
  cleanly separated with Release strictly gated on Build success.

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
  STUN/TURN ice servers against the configured production TURN hostname.
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
