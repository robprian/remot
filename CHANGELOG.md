# Changelog

All notable changes to Remot are documented here.

The format follows the project convention: every version entry contains the
version, date, short summary, changed components, bug fixes, and notable
technical changes.

Production versions use the V/C/P scheme (see `docs/VERSIONING.md`):
`V1C001`, `V1C001P01`, ... Legacy semver releases are preserved below under
`Legacy` entries. New versions are always inserted at the top.

---

## V2C004P03 — 2026-08-29

### Summary

Adds on-device network debugging so "Signaling Unreachable" can be diagnosed
from the phone instead of by guesswork: a live **Signaling debug log** in
Diagnostics that records every WebSocket connect attempt, endpoint, and the
exact OS-level error with a copy-to-clipboard button, plus **Chucker**, an
on-device HTTP inspector for the app's other requests (GitHub update check).

### Changed

- Bumped production version to **V2C004P03** (versionCode 200403).

### Added

- **`SignalingDebugLog`** — a bounded, thread-safe, in-memory log of the
  signaling WebSocket lifecycle (CONNECTING / CONNECTED / FAILED / CLOSED /
  REGISTER-FAILED), including which fallback endpoint was tried and the raw
  connection error (e.g. `Software caused connection abort`). WebSocket does
  not go through OkHttp interceptors, so Chucker cannot show it — this is the
  authoritative on-device record, also mirrored to logcat as `RemotSignaling`.
- **Diagnostics → “Signaling debug log”** card listing the most recent
  attempts with a **Copy log** button (newline-separated plain text to paste
  to the developer) and the last-failure summary.
- **Chucker** (`com.github.chuckerteam.chucker:library:4.0.0`; 4.3.x needs
  compileSdk 36, out of scope for this project's AGP toolchain) wired to the
  update-checker HTTP client, plus an **HTTP inspector** button in the same
  card that opens the Chucker UI. ProGuard keep rules added for the minified
  release build.

### Notes

- Chucker does **not** capture WebSockets (ChuckerTeam/chucker#675); it is
  included for the app's HTTP traffic, while the actual signaling diagnosis
  uses `SignalingDebugLog`.

---
## V2C004P02 — 2026-08-29

### Summary

Verification + CI patch: the cloud security group is now open and the full
signaling → STUN → TURN path is proven reachable from the public internet,
and the external probe now also performs a real TURN Allocate check so every
build verifies the relay endpoint from GitHub's network. No app logic changed;
this release exists so installed devices get a fresh versionCode and a
verifiably current APK.

### Changed

- Bumped production version to **V2C004P02** (versionCode 200402).

### Verified (server, after the firewall was opened)

- **STUN via public IP:** real Binding round-trip `43.156.82.52:3478` → OK
  (1–2 ms) and `turn.robrion.net:3478` → OK (9 ms); before the security-group
  change this timed out from the same vantage point.
- **TURN Allocate:** full authenticated Allocate (401 realm/nonce challenge →
  HMAC-SHA1 response with the short-lived credentials the signaling server
  issues) succeeds against the production coturn via both the hostname and the
  direct public IP. coturn `static-auth-secret` matches the server `TURN_SECRET`
  (verified by hash), realm and relay range (49152–65535) are correct.
- **Signaling:** still reachable from GitHub's public network (TCP, HTTP
  /healthz, WS 101, register round-trip) — unchanged.
- Note: TURNS TCP 5349 remains closed (no TLS cert) and is no longer
  advertised by the server; UDP relay media additionally needs the 49152–65535
  UDP range open in the security group for actual relayed sessions (the app's
  health check passes on the Allocate itself).

### Added

- `scripts/probe-endpoints.mjs` now also sends a real (unauthenticated) TURN
  Allocate and expects the 401 challenge — so the CI `network-probe` job
  verifies the TURN relay endpoint, not just STUN, from GitHub's network on
  every build. REQUESTED-TRANSPORT is encoded per RFC 5766 (protocol byte
  first); this matches the app's `StunTurnProbe` implementation.

---
## V2C004P01 — 2026-08-29

### Summary

Server + CI patch: makes the signaling WebSocket provably reachable from the
public internet, hardens the broker against stalled connections, stops
advertising a TURNS (TLS) endpoint that cannot work yet, and adds a permanent
external endpoint probe to every build so server reachability is verified
from GitHub's network on each push. No Android app code changed; the version
bump follows the mandatory-P rule so installed V2C004 devices see the update
prompt.

### Changed

- Bumped production version to **V2C004P01** (versionCode 200401).

### Fixed (server — deployed via systemd)

- **Signaling was verified running and reachable:** the broker runs under
  `remot-signaling.service` with auto-restart (systemd restarted it in 3 s
  when the process was killed), and `http://<public-ip>:8080/healthz` was
  fetched successfully from an external vantage point (`r.jina.ai`). A raw
  WebSocket upgrade returns `101 Switching Protocols` and a full register
  round-trip answers `register-failed` — the server message loop is alive.
- **Stalled registrations now time out:** a client that sent `register` but
  never answered the auth challenge could hold a socket (and an IP) open
  forever. The broker now closes it after 15 s (`auth-timeout`) and clears the
  timer on success/close.
- **Diagnosability:** the broker now logs `ws_connect` (ip) and
  `register_failed` (ip, reason) as structured JSON, so "can't connect"
  reports can be checked against the actual server log.
- **Dead TURNS URL removed:** coturn has no TLS certificate, so the
  advertised `turns:host:5349` ICE server could never connect. The signaling
  server now issues only `stun:` + `turn:` (UDP) until a certificate is
  provisioned (commented in `server/src/turn.js`).

### Added (CI external probe)

- `scripts/probe-endpoints.mjs` — dependency-free external probe that checks
  TCP :8080, HTTP `/healthz`, the WebSocket upgrade, a full `register`
  round-trip, a real STUN Binding over UDP :3478, and TURN TLS :5349.
  Endpoints come from `SERVER_URL`/`SERVER_IP` env vars (GitHub secrets) — no
  IPs are hardcoded. Exits non-zero only when signaling is unreachable;
  STUN/TURN results are reported without failing.
- `build.yml` gains a `network-probe` job (GitHub-hosted runner = external
  network) that runs the probe on every build with `continue-on-error: true`,
  so a cloud-firewall-blocked TURN never blocks Android releases while
  signaling reachability is visible in every run log.

### Ops note

- Verified on the server: coturn answers STUN locally (0 ms), the relay range
  is 49152–65535, `external-ip` is set, and auth-secret HMAC credentials are
  issued. STUN/TURN remain `unreachable` from devices only because the cloud
  security group still drops 3478/5349 inbound — open UDP+TCP 3478 (and 5349
  once TLS is configured) in the Alibaba Security Group; the app and probe
  will report them online immediately after.

---
## V2C004 — 2026-08-29

### Summary

Publishes the connectivity and pairing fixes accumulated after V2C003 as a
fresh production version, so devices on V2C003 receive them through the
in-app update prompt (versionCode 200400 > 200300). Includes IPv4-first
signaling/STUN-TURN resolution, automatic signaling endpoint fallback,
a working "Pair a new device" flow, and the developer GitHub link.

### Changed

- Bumped production version to **V2C004** (`versionName`), `versionCode`
  **200400**.

### Notes

- All functional changes in this version are described in detail under the
  V2C003 entry (Signaling/STUN-TURN IPv4-first, endpoint fallback rotation,
  Pair a new device wiring, developer link); this entry exists to roll them
  out to installed V2C003 devices as an auto-update-visible release.

### Tooling

- Added `scripts/bump-version.sh` — one-command V/C/P version bump for small
  patches (`V2C004` → `V2C004P01`, versionCode 200400 → 200401) and change
  cycles, updating `build.gradle.kts` + CHANGELOG. The rule is now explicit:
  **every release, including small patches, must raise versionCode** so the
  in-app auto-update prompt always fires (documented in `docs/VERSIONING.md`).

---

## V2C003 — 2026-08-29

### Summary

Adds an **in-app auto-update** flow: on launch the app checks the latest
published Remot GitHub release, and if it is a newer V/C/P version than the
installed build, shows a dialog offering to download and install the new APK
from the GitHub release. Download happens in-app (with progress) and the APK
is handed to the system package installer — installation always stays an
explicit platform-gated user action.

### Added

- `UpdateChecker` — queries `repos/robprian/remot/releases/latest`, derives the
  release `versionCode` from its V/C/P tag on the same formula as the APK
  (`V*100000 + C*100 + P`), and returns the APK asset URL. Never prompts on a
  transient GitHub/network failure.
- `ApkInstaller` — streams the APK into app-private storage (OkHttp) with
  progress, then launches the system installer via a new FileProvider
  (`com.robrion.remot.fileprovider`, `@xml/file_paths`). Falls back to guiding
  the user to allow installs from this source when needed.
- `REQUEST_INSTALL_PACKAGES` permission + FileProvider wiring in the manifest.
- Non-blocking Compose update dialog (available / downloading / install-started
  / error) surfaced at the app root, checked on foreground and throttled to
  avoid hammering the GitHub API.

### Fixed

- **Cleartext signaling:** the app runs targetSdk 35, which blocks `ws://`
  cleartext by default — so a plain `ws://` signaling endpoint never connects,
  leaving **Signaling=unreachable** and **STUN/TURN=unknown** (no credentials are
  ever issued). Enabled `usesCleartextTraffic` so `ws://` signaling can connect
  over the internet; `wss://` remains supported. Prefer `wss://` for production.
- **Developer diagnostics in-app:** the Services & Diagnostics screen now shows
  the compiled signaling endpoint, the last signaling connection error (e.g.
  cleartext/DNS/TLS/rejected), a note explaining STUN/TURN credentials arrive
  once signaling connects, and a Developer card with build/versionCode/applicationId.
- **ProGuard/R8 keep rules** for the AccessibilityService, NotificationListener,
  and `ServiceStatus` so minified release builds never rename/strip the
  system-bound service classes (keeps enablement and state detection working).

### Documentation

- Completely redesigned `README.md` as a professional, scannable project page:
  hero + badge set, contents navigation, overview, features, architecture
  diagram, requirements, installation, usage, permissions, network
  architecture, building, releases, project status, security, development,
  roadmap, contributing, and license.
- Added navigation linking **README ↔ CHANGELOG ↔ GitHub Releases ↔ docs/**.
- README shows the current version and links directly to `CHANGELOG.md` and
  GitHub Releases; no infrastructure IPs or secrets are exposed.
- Updated the `LICENSE` copyright holder to match the intended project
  maintainer.

### CI/CD

- Converted the release pipeline to a **rolling release**: a successful
  `main` build now releases automatically — same version updates the existing
  tag/release/APK in place; a new version creates a new tag/release.
- Release workflow downloads the **exact** successful Build artifact, validates
  it (package, versionName/versionCode, signature), reads the version from the
  APK, and never runs Gradle.
- Same-version releases force-move the tag to the newest build commit and
  replace the APK asset via `gh release upload --clobber`; no duplicate tags.
- Failed builds continue to block release; release concurrency prevents
  duplicate/overlapping releases; tag manipulation can no longer trigger a
  second build, so the pipeline is acyclic.
- Build workflow now runs on `main` pushes only (still GitHub-hosted),
  containing the single authoritative production APK build.

### Repository

- Added R8 keep rules so minified builds preserve the AccessibilityService,
  NotificationListener, and status detection.
- Contributor attribution had already been normalized earlier so all Git
  history attributes authorship to the project maintainer (GitHub's
  contributor graph recalculates on its own schedule after the force-push).

### Added (TURN resilience + server config)

- **TURN/STUN auto-restart:** `infra/remot-coturn.service` and
  `infra/remot-signaling.service` — systemd units that restart coturn and the
  signaling broker automatically whenever they die (`Restart=always`,
  `RestartSec=3`, burst limits).
- `infra/remot-watchdog.sh` — a liveness watchdog that health-checks the
  signaling `/healthz` and performs a real STUN Binding over UDP against the
  live coturn; if either is down (or hung-but-active), it restarts the unit
  via systemctl, so a dead TURN server comes back by itself. Includes a small
  dependency-free Python STUN probe.
- `infra/check-ports.sh` — external reachability checker. Run from a machine
  **outside** the VPS to confirm 8080, 3478 (TCP+UDP), 5349, and the relay
  range are open from the internet.

### Changed (server endpoint wiring)

- Server endpoints are now sourced **only from GitHub Secrets** — never
  hardcoded: `SERVER_URL` (signaling; used as a fallback for `SIGNALING_URL`)
  and `SERVER_IP` (direct public IP fallback for STUN/TURN health probes).
  Builds pass these through the `build.yml` env and expose them via
  `BuildConfig.SERVER_URL` / `BuildConfig.SERVER_IP`.
- `NetworkHealthRepository` now falls back to the configured `SERVER_IP` for
  the STUN/TURN probe when the hostname the signaling server issued is
  unreachable (DNS/STUN/timeout) — so a direct-IP connection is attempted
  before reporting TURN offline, without embedding any address in the APK
  source.

### Fixed

- **TURN/STUN ports were `filtered` externally:** an external scan of the VPS
  showed 3478/5349 TCP `filtered` and UDP closed (only 8080 was open) — the
  exact cause of the app reporting STUN/TURN unreachable. The TURN relay
  cannot work until the firewall/security-group opens UDP+TCP 3478, TCP 5349,
  and UDP 49152–65535 for the public IP. Documented in `infra/README.md` and
  surfaced by `check-ports.sh` (elevated to a gateway above).
- **Signaling `Software caused connection abort`:** the deployment's signaling/
  TURN hostname (`turn.robrion.net`) resolves to **IPv6 (AAAA) first** and
  IPv4 after; on networks without a routable IPv6 path the IPv6 leg can be
  aborted before OkHttp/InetAddress falls through to the working IPv4 address,
  so signaling showed unreachable even though the server answered `200 OK` on
  IPv4. The signaling `OkHttpClient` and the `StunTurnProbe` DNS resolution
  now **prefer IPv4**, so the IPv6 leg is skipped when IPv4 is available.
- **Maintainer link in-app:** the Developer card on the System Services screen
  now shows a tappable **`@robprian ↗`** link that opens
  `https://github.com/robprian/remot`.
- **"Pair a new device" button did nothing:** the button on the Paired devices
  screen had an empty click handler. It now starts the real authenticated
  pairing flow — the device shows its signed `PairingOffer` QR (new Pair QR
  screen), and the other device scans it ("Scan a pairing code" button). The
  QR scanner now forwards any text payload verbatim so both session codes and
  pairing offers are recognised, completing the ECDH exchange and surfacing
  the safety-number confirmation as before.

#### Signaling end-to-end verified locally
- The signaling server runs **locally** (`server/`, `node src/server.js`,
  `/healthz` → `200 ok`) and its full smoke suite passes (`20 passed, 0
  failed`): signed registration, auth challenge, iceServers issuance, pairing,
  join/consent, offer/answer/ice relay, invalid-code and rate limiting.
- A raw WebSocket probe confirms the **production signaling endpoint answers**
  (`ws://turn.robrion.net:8080` → `[OK] open in ~21ms`) and the direct-IP
  route works too (`ws://43.156.82.52:8080` → `[OK] open in ~10ms`). The
  server is not dropping ws connections.
- **Fallback endpoints added:** `SignalingClient` now accepts an ordered list of
  candidate URLs and rotates through them on connect failure. `ServiceLocator`
  builds: primary `SIGNALING_URL`, a `wss://` variant of the same host:port,
  a `ws://` direct endpoint to `BuildConfig.SERVER_IP`, and a `wss://` variant
  to that IP (when configured). If the primary is unreachable — e.g. a
  hostname resolving to unroutable IPv6 first, or a network blocking cleartext
  `ws://` — the client automatically retries over the alternate routes.
  Diagnostics now appends the endpoint being used and notes that alternate
  endpoints are retried automatically.

### Versioning

- Bumped production version to **V2C003** (`versionName`), `versionCode`
  **200300** — higher than the shipped V2C002 (200200) so the update check
  reads it as current once installed.

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
