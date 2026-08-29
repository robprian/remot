# Remot

### Remote Android Control — Anywhere, Over the Internet

Remot is an Android remote-control platform that securely connects two Android
devices over the Internet. It streams the screen over **WebRTC** and injects
touch and keyboard input through Android's **AccessibilityService**, using a
signaling broker plus **STUN/TURN** so devices behind NAT and carrier CGNAT can
still connect.

[Features](#features) · [Architecture](#architecture) · [Requirements](#requirements) ·
[Installation](#installation) · [Usage](#usage) · [Network](#network-architecture) ·
[Building](#building) · [Releases](#releases) · [Changelog](./CHANGELOG.md) ·
[Security](#security) · [Development](#development) · [Roadmap](#roadmap) ·
[Contributing](#contributing) · [License](#license)

[![Build](https://img.shields.io/github/actions/workflow/status/robprian/remot/build.yml?branch=main&label=build)](https://github.com/robprian/remot/actions)
[![Release](https://img.shields.io/github/v/release/robprian/remot)](https://github.com/robprian/remot/releases)
![Android](https://img.shields.io/badge/Android-7.1%2B-3ddc84?logo=android)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
- [Permissions](#permissions)
- [Network Architecture](#network-architecture)
- [Building](#building)
- [Releases](#releases)
- [Project Status](#project-status)
- [Security](#security)
- [Development](#development)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

Remot is built entirely on public Android platform APIs — no root, no hidden
hooks. One device (the **host**) shares its screen and accepts input; another
device (the **controller**) views and controls it. Sessions are consent-first:
the host always approves before access begins and a persistent notification
clearly shows that control is active.

**Current version:** [V2C003][releases-latest] · see [CHANGELOG.md](./CHANGELOG.md).

---

## Features

- **Android-to-Android remote control** over the Internet.
- **WebRTC transport** — encrypted media and control over DTLS-SRTP.
- **STUN/TURN NAT traversal** — direct P2P when possible, TURN relay as a fallback.
- **Remote interaction** via Android AccessibilityService: tap, long-press,
  swipe/drag, Back/Home/Recents, and text input.
- **Notification Listener integration** to surface host notifications to an
  active session.
- **Consent-first sessions** with one-time codes, QR join, and optional
  unattended access for trusted paired devices.
- **Connection health monitoring** — Internet, Signaling, STUN, and TURN status
  with **real measured TURN latency** (never faked from DNS or config).
- **WebRTC route diagnostics** — shows whether the active session uses a direct
  path, STUN (`server-reflexive`), or a TURN relay.
- **In-app updates** — detects a newer published release and can download and
  install it from GitHub Releases.
- **Android 7.1 → 16** compatibility (see [docs/ANDROID_COMPATIBILITY.md](docs/ANDROID_COMPATIBILITY.md)).

---

## Architecture

```
                 Internet
                    │
          ┌─────────┴─────────┐
          │                   │
    Android A            Android B
    (Controller)           (Host)
          │                   │
          └─────────┬─────────┘
                    │
             Signaling server        (brokers setup, pairing, codes)
                    │
              STUN / TURN            (NAT traversal)
                    │
                 WebRTC               (encrypted media + control)
```

- **Host** — captures its screen with `MediaProjection` and receives input via
  an AccessibilityService bound by the system.
- **Controller** — renders the host's live video and sends gestures/keys over a
  WebRTC data channel.
- **Signaling server** — brokers registration, session codes, pairing, and
  WebRTC offers/answers/ICE. It sees metadata only, never your screen.
- **STUN/TURN** — STUN discovers direct paths; TURN relays encrypted media only
  when a direct path is unavailable.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/REMOTE_PROTOCOL.md](docs/REMOTE_PROTOCOL.md) for details.

---

## Requirements

- **Two Android devices** (Android 7.1+), each running the Remot APK.
- A **signaling server** reachable by both devices over the network.
- **TURN** (coturn) for cross-network sessions behind carrier CGNAT.

---

## Installation

1. Download the latest production APK from
   [GitHub Releases][releases-latest]. The APK is **built, signed, and
   validated by the GitHub Actions production pipeline** — never by hand.
2. Install it on both devices and allow installs from this source if prompted.
3. On first run, grant **Notification access** and enable the **Remot Control
   Service** under *Accessibility settings* (needed to control the host).

---

## Usage

1. **Host:** tap **Share my screen** and approve the consent and screen-capture
   dialogs. A 6-digit code (and QR) appears.
2. **Controller:** tap **Connect**, enter the code (or scan the QR).
3. The host approves; the controller sees the live screen and can tap, swipe,
   long-press, press Back/Home/Recents, and type.
4. Either side taps **End session**; the notification clears and the session
   closes.

For unattended access to a trusted paired device, pair once and grant access.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Permissions

Remot requests only the permissions it actually uses:

| Permission / capability     | Purpose                                              |
| --------------------------- | ---------------------------------------------------- |
| `MediaProjection`           | Share the host's screen                               |
| `AccessibilityService`      | Inject taps, swipes, keys, and text (system-gated)    |
| `NotificationListener`      | Surface host notifications during a session           |
| Internet / Network state    | Signaling + WebRTC connectivity                       |
| Camera (optional)           | Scan a session QR                                     |
| `REQUEST_INSTALL_PACKAGES`  | Install an in-app update                              |

None of the captured screen content, notifications, or input is uploaded to any
server — media flows peer-to-peer (or through a TURN relay) and stays encrypted.

---

## Network Architecture

Remot uses **WebRTC** with **STUN/TURN** so devices behind carrier CGNAT can
still connect across the Internet. STUN enables direct P2P discovery; TURN
relays encrypted media only when no direct path exists. Time-limited TURN
credentials are issued at runtime by the signaling server — the APK never ships
with a TURN secret embedded.

See [docs/ANDROID_COMPATIBILITY.md](docs/ANDROID_COMPATIBILITY.md) and the
infrastructure notes in `infra/` for deployment details.

---

## Building

Production APKs are built on **GitHub-hosted GitHub Actions runners**:

- `build.yml` — runs tests, lint, `assembleRelease`, signs with the production
  keystore from GitHub Secrets, validates the APK, and uploads it as an
  artifact.
- `release.yml` — runs only after a **successful** Build, downloads that exact
  artifact, re-validates it, and publishes it to GitHub Releases.

The project does **not** build the Android app on its production/infrastructure
server; that server runs runtime services (signaling, TURN) only. Local
development builds work with Android Studio:

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the full build, test, and
release process, and [docs/VERSIONING.md](docs/VERSIONING.md) for the V/C/P
version scheme.

---

## Releases

- **Latest production APK:** [Download latest release][releases-latest]
- **All releases:** [View GitHub Releases][releases]
- **Full version history:** [CHANGELOG.md](./CHANGELOG.md)

Release APKs follow a deterministic name, for example `remot-V2C003.apk`.

---

## Changelog

See the complete, authoritative release history in **[CHANGELOG.md](./CHANGELOG.md)**.
Newest versions are always listed first; historical versions are never deleted.

---

## Project Status

Remot is under active development. Production builds are generated and
validated automatically through GitHub Actions. The core remote-control flow is
functional; expect ongoing compatibility, diagnostics, and device-management
improvements.

---

## Security

- WebRTC is used for media/data transport, encrypted end to end.
- TURN is used only when direct connectivity is unavailable; media remains
  encrypted through the relay.
- Sensitive credentials are **never** stored in the repository or compiled into
  the APK.
- Production signing credentials are stored in **GitHub Secrets**
  (`RELEASE_KEYSTORE_*`); the keystore is never committed.
- Infrastructure secrets and IP addresses must never be committed.

See [docs/SECURITY.md](docs/SECURITY.md) for the full threat model.

---

## Development

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — build, test, and release process.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — system and transport design.
- [docs/REMOTE_PROTOCOL.md](docs/REMOTE_PROTOCOL.md) — signaling/control protocol.
- [docs/ANDROID_COMPATIBILITY.md](docs/ANDROID_COMPATIBILITY.md) — Android 7.1–16 support.
- [docs/VERSIONING.md](docs/VERSIONING.md) — V/C/P versioning and versionCode mapping.

---

## Roadmap

- [x] Android remote-control foundation
- [x] WebRTC transport + consent-first sessions
- [x] STUN/TURN NAT traversal
- [x] Connection health & TURN latency diagnostics
- [x] Production APK pipeline (GitHub Actions)
- [x] In-app update from GitHub Releases
- [ ] Advanced device management
- [ ] Deeper session diagnostics
- [ ] Additional Android compatibility improvements

---

## Contributing

Please open an issue before proposing major architectural changes. All
meaningful changes should add an entry at the top of `CHANGELOG.md`. Production
releases are generated through the GitHub Actions pipeline.

---

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file.

---

[releases]: https://github.com/robprian/remot/releases
[releases-latest]: https://github.com/robprian/remot/releases/latest