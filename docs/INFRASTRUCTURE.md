# Infrastructure

Remot separates **build/release** from **runtime** onto different machines. This
is a deliberate design rule, not a convenience: the runtime server is small and
must stay responsive, and production Android artifacts must be reproducible on
build infrastructure.

```
GitHub Actions (hosted runner)
    |
    |  Android SDK + Java + Gradle
    |  test + lint + assembleRelease
    |  production signing + APK validation
    |  artifact upload
    v
GitHub Release
    |
    v
Verified APK artifact

VPS (runtime only)
    |
    |  Coturn (STUN/TURN)
    |  Signaling server
    |  WebSocket server
    |  API / backend + monitoring
```

## GitHub Actions — Android build & release

GitHub-hosted runners are the **only** place production Android artifacts are
built, signed, validated, and released.

- `build.yml` is the single authoritative production build: it checks out the
  code, sets up Java + the Android SDK, runs unit tests and lint, assembles the
  signed release APK, validates it (application ID, version, signature), and
  uploads it as an artifact.
- `release.yml` never rebuilds. It is triggered only by a **successful** Build
  run (`workflow_run`), downloads the exact artifact that build produced,
  re-validates it, and publishes the GitHub Release with that APK attached.
- A failed Build blocks Release — Release must not run after a failed build.

## VPS — runtime services only

The VPS runs Remot's runtime infrastructure:

- **Coturn** — STUN server + TURN relay for WebRTC NAT traversal.
- **Signaling server** — WebSocket broker for session setup, pairing, and
  authenticated registration. It issues time-limited STUN/TURN credentials to
  clients at runtime.
- **API / backend**, **monitoring**, and other runtime services.

The VPS is **never** used for building: no Gradle, no Android SDK compilation,
no APK assembly or signing, no self-hosted GitHub Actions runner. Android
builds must never be executed on the VPS or pushed to it via SSH.

## What this guarantees

- Exactly one authoritative Android production build.
- The APK that is released is the exact validated artifact from the build
  that succeeded — never a rebuild at release time and never an older run.
- The VPS stays lightweight and responsive for runtime traffic.
- Builds are reproducible and do not depend on whatever happens to be
  installed on the runtime server.