# Remot — Development

## Prerequisites

- **Android:** JDK 17, Android SDK (API 35 platform, build-tools 34.0.0),
  Android Studio (Koala+) optional.
- **Server:** Node.js >= 18 (20+ recommended).

## 1. Signaling server

```bash
cd server
npm ci
npm run smoke        # protocol end-to-end test, no Android needed
npm start            # ws://0.0.0.0:8080
```

### Configuration

`server/.env.example` → copy to `server/.env`. On Node 20+:

```bash
node --env-file=.env src/server.js
```

## 2. Android app

```bash
cd android
# Point local.properties at your SDK, then:
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # lint
```

The debug build connects to `ws://10.0.2.2:8080` (emulator → host loopback) by
default. For two real devices (or a production build) supply a `SIGNALING_URL`
at build time — as a Gradle property (`-PSIGNALING_URL=wss://...`),
`SIGNALING_URL` env var, or a `SIGNALING_URL` GitHub secret (used by CI). The
endpoint is not hardcoded in source.

### FCM wake (optional)

Add a real `google-services.json` (never commit it), uncomment the
`com.google.gms.google-services` plugin lines in `android/build.gradle.kts` and
`android/app/build.gradle.kts`, and set `FCM_ENABLED=true` + service-account
credentials on the server. Without it, the app builds and runs; only
offline-host wake is inert.

## 3. Two-device verification checklist

Requires two physical Android devices (MediaProjection, AccessibilityService,
and WebRTC cannot meaningfully run in a single emulator):

1. Start the signaling server; point both devices at it (LAN IP for dev).
2. Device A (host): **Share my screen** → approve consent + MediaProjection.
3. Device B (controller): **Connect** → enter the 6-digit code.
4. Verify: video renders, tap/long-press/swipe/Back/Home/Recents/text work,
   persistent notification shows, End session cleans up.
5. Network tests: Wi-Fi ↔ cellular switch (ICE restart), airplane mode +
   return, TURN-only path (block P2P, e.g. force relay), reconnect after
   signaling server restart.
6. Pairing: QR pair two devices, compare safety numbers, then dial the paired
   host directly.

## 4. Release process

Production versions use the V/C/P scheme (see `docs/VERSIONING.md`):
`V1C001`, `V1C001P01`, ... One commit per logical change; every meaningful
change is recorded at the top of `CHANGELOG.md`.

1. Run the release gate locally — it checks git state, secrets, version
   format, CHANGELOG, Android `versionName`/`versionCode`, then runs unit
   tests, lint, the release build, and verifies the APK metadata:

   ```bash
   ./scripts/release-check.sh v1c001
   ```

2. Create the production tag and push it:

   ```bash
   git tag v1c001
   git push origin v1c001
   ```

Production releases use **two separated workflows**:

- `build.yml` **Remot Build** — runs on pushes to main, PRs, and V/C/P tags.
  It runs unit tests and lint, assembles the signed release APK, validates its
  application ID, version, and signature, and uploads the exact APK as an
  artifact (`remot-release-apk`). It never creates a release.
- `release.yml` **Remot Release** — triggered **only** by a successful `Remot
  Build` completion (`workflow_run` with `conclusion == success`). For V/C/P
  tags it downloads the exact artifact, re-validates it, and creates the
  GitHub Release. It never rebuilds. Non-tag builds and failed builds never
  reach Release, so there is exactly one release path.

The tag pushes the build (and thereby the gated release) for that exact
commit. Concurrency prevents duplicate builds and only one release runs at a
time.

### Signing

Release signing requires the persistent production keystore. Credentials are
supplied at build time from Gradle properties or environment variables
(`RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`), and via GitHub Secrets in CI
(`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`). The release build **fails** if any signing value is
missing — it never falls back to debug signing or emits an unsigned production
APK. Keystores are never committed. The debug APK is signed with the debug key
and installable directly.

## 5. GitHub Actions cost control

- Local verification first: `npm run smoke`, `:app:assembleDebug`,
  `:app:testDebugUnitTest`, `:app:lintDebug`.
- Workflows run on push to main, PRs, and version tags only. No per-commit
  matrix builds; one canonical build environment; Gradle caching enabled;
  superseded runs cancelled via concurrency.
- Do not run CI after every local edit.

## 6. Code style & structure

- Manual DI via `ServiceLocator` (no Hilt/Koin — the wiring lives in one file).
- Platform APIs preferred over extra dependencies.
- Pure logic (e.g. `SessionCodes`) lives free of Android types for JVM tests.
- New user-visible or technically meaningful changes require a CHANGELOG entry.
