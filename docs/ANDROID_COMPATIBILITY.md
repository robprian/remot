# Remot — Android Compatibility

Target range: **Android 7.1 (API 25) through Android 16 (API 36)** where
technically possible.

| Setting | Value |
| --- | --- |
| minSdk | 25 (Android 7.1) |
| targetSdk | 35 (Android 15) |
| compileSdk | 35 |

---

## 1. Why targetSdk 35 on Android 16

An app targeting SDK 35 runs on Android 16 (API 36) unchanged — the OS applies
targetSdk-based behavior only when an app *targets* a higher SDK. The notable
Android 16 changes (edge-to-edge enforcement, 16 KB page sizes, predictive
back) are handled as follows:

- **Edge-to-edge** is enforced for apps targeting 36 — not applicable at
  targetSdk 35; the app already draws full-bleed Compose surfaces.
- **Predictive back** (Android 15+/16) — the app uses standard activity
  navigation; no custom back handling that would conflict.
- **16 KB page sizes** — on 16 KB-page devices (some Android 15/16 devices),
  native libraries must be 16 KB-aligned. Remot depends on the WebRTC prebuilt
  (`io.github.webrtc-sdk:android`), whose `.so` alignment must be verified on
  such hardware. **Limitation:** cannot be verified without a 16 KB-page
  device; documented for the production rollout.

A future release may move to compileSdk/targetSdk 36 (requires AGP ≥ 8.9.1 +
Gradle 8.11.1+); this is tracked as a follow-up, not a V1C001 blocker.

---

## 2. Version-by-version notes

### Android 7.1 / 8.0 (API 25 / 26)

- minSdk 25. The only API-26+ feature used is `NotificationChannel`, guarded
  at runtime (`Notifications.ensureChannels` is a no-op below 26).
- All crypto (P-256 signing, ECDH) works on API 25 via `java.security` and
  Android Keystore.
- Accessibility `dispatchGesture` requires API 24+ — fine.

### Android 10 (API 29)

- Foreground services must declare a type; guarded:
  `startForeground(id, notif, FOREGROUND_SERVICE_TYPE_*)` on 29+.

### Android 12 (API 31)

- Foreground-service start restrictions: sessions are user-initiated from the
  foreground, so start is permitted; the wake path uses FCM high-priority
  pushes which are exempt.

### Android 13 (API 33)

- `POST_NOTIFICATIONS` runtime permission — requested on the Home screen;
  the app still works (sessions run, capture notification is required by
  policy) if denied.
- Typed parcelable extras (`getParcelableExtra(name, cls)`) guarded at 33+.

### Android 14 (API 34)

- Foreground service types declared in the manifest:
  `mediaProjection` (capture/unattended) and `connectedDevice` (signaling
  reconnect). Foreground-service launch from the background on wake is covered
  by the FCM high-priority exception.
- `USE_FULL_SCREEN_INTENT` requires user opt-in in Settings for
  non-calling/alarm apps on 14+; Remot degrades to a standard heads-up
  notification if not granted.

### Android 15 / 16 (API 35 / 36)

- Runs as documented above (targetSdk 35).

---

## 3. Remote-control mechanism per version

| Capability | Mechanism | Notes |
| --- | --- | --- |
| Screen capture | MediaProjection + `mediaProjection` FGS | Per-session system consent (11+ single-use Intent caveat below) |
| Tap / swipe / long-press | `AccessibilityService.dispatchGesture` | API 24+ |
| Back / Home / Recents | `performGlobalAction` | |
| Text input | `ACTION_SET_TEXT` on focused node | Requires the remote app to expose an editable node |

## 4. Known platform limitations (honest list)

1. **Unattended access (Android 11+):** a MediaProjection permission Intent is
   effectively single-use. The scaffold stores the grant Intent; a production
   unattended build must keep the capturer/track alive across sessions rather
   than rebuilding from a stored Intent. This is a documented platform ceiling
   for the V1C001 unattended path.
2. **Input injection scope:** `dispatchGesture` injects at the screen level;
   apps using custom views/non-accessible surfaces may not respond to
   `ACTION_SET_TEXT`. Gestures generally work everywhere.
3. **Multi-touch:** gesture dispatch is single-stroke; true multi-touch is not
   implemented (P2 roadmap).
4. **16 KB page-size devices (Android 15/16):** depends on WebRTC `.so`
   alignment (see §1).
5. **OEM restrictions:** some OEMs restrict background activity, notification
   channels, or accessibility enablement. Remot shows actionable guidance
   (Settings links) but cannot override OEM policy.
6. **System dialogs / secure screens:** MediaProjection deliberately cannot
   capture secure surfaces (e.g. DRM video, some banking screens); the remote
   screen shows a blank/black area — this is platform behavior, not a bug.
7. **Device audio:** not streamed in V1C001 (`RECORD_AUDIO` intentionally
   absent from the manifest).
8. **FCM wake:** requires a Firebase project + `google-services.json`
   (never committed). Without it, offline-host wake is inert; attended
   sessions are unaffected.
