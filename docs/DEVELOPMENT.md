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

The debug build connects to `ws://10.0.2.2:8080` (emulator → host loopback).
For two real devices, override `SIGNALING_URL` in `app/build.gradle.kts` (debug
build type) or set the release URL to your deployed `wss://` endpoint.

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

Semantic versioning. One commit per logical change; every meaningful change
recorded in `CHANGELOG.md`.

```bash
# bump versionName/versionCode in android/app/build.gradle.kts
git tag v1.0.0
git push origin v1.0.0
```

The tag triggers `android-release.yml`, which builds + tests + lints the app,
runs the server smoke test, uploads artifacts, and creates a **GitHub
Release** with the APKs (`remot-v1.0.0.apk` release build, `remot-v1.0.0-debug.apk`).

### Signing

Release signing uses environment secrets if configured (`KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Without them the release APK
is built unsigned — it must be signed before install. The debug APK is signed
with the debug key and installable directly. Keystores are never committed.

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
