# Remot Versioning (V/C/P)

Remot does **not** use ordinary semantic versioning as its primary production
identifier. Production versions use a three-level scheme:

```text
V{major}C{change}P{patch}
```

Example: `V1C001P37` — base generation `V1`, change cycle `C001`, patch `P37`.

## Version components

### V — Base production generation

The major product/architecture generation. Increment only for:

* a major architecture redesign
* a major protocol change
* a major product generation
* a breaking architecture change
* a substantial platform migration

Examples: `V1`, `V2`, `V3`.

### C — Production change cycle

A substantial production change *inside* a V generation. Always three digits:

```text
C001, C002, C003, ...
```

Use C for substantial changes such as:

* a significant feature
* a major bug resolution
* a major networking improvement
* a substantial UI redesign
* an important security improvement
* a major performance improvement

### P — Minor production patch

A smaller patch on the current V/C cycle. Always two digits:

```text
P01, P02, ... P99
```

Use P for:

* a small bug fix
* a crash fix
* a compatibility fix
* a small UI fix
* a minor performance fix
* a CI fix
* a small security fix

## Version decision rule

* **V** — only major product generations.
* **C** — substantial production changes.
* **P** — minor patches.

When uncertain, prefer the smallest increment that accurately describes the
production impact. Do not inflate versions.

## Every release MUST raise versionCode (auto-update rule)

The in-app auto-update compares the installed APK's `versionCode` against the
latest published release's `versionCode` (`V*100000 + C*100 + P`). Therefore
**every release — including every small patch — MUST carry a higher
versionCode than the last published release.** In practice:

* A small patch on the current cycle bumps **P**: `V2C004` → `V2C004P01` →
  `V2C004P02` (versionCode 200400 → 200401 → 200402).
* A substantial change bumps **C** and resets P: `V2C004P99` → `V2C005`.
* A new generation bumps **V** and resets C/P: `V2C999P99` → `V3C001`.

Never re-release the same versionCode twice — devices on that version would
never see the update prompt. If you are about to push an APK whose version
has not changed since the last release, bump it first.

Use `scripts/bump-version.sh` for this:

```bash
./scripts/bump-version.sh             # small patch  -> next P (default)
./scripts/bump-version.sh --change    # change cycle -> next C, P reset
./scripts/bump-version.sh --dry-run   # preview only
```

It updates `android/app/build.gradle.kts` (versionName/versionCode) and
inserts the new top CHANGELOG entry. Versions still represent **production
states**, not commits — but because every release is a production state, each
release bumps.

## Git tag format

Git tags exactly represent the production version, lowercase:

```text
v1c001
v1c001p01
v1c001p37
v1c002
v2c001
```

The tag maps directly to `V1C001`, `V1C001P01`, etc. The release workflow
triggers only on tags matching `v[0-9]c[0-9][0-9][0-9]` or
`v[0-9]c[0-9][0-9][0-9]p[0-9][0-9]`.

## Android versioning

* `versionName` carries the production identifier, e.g. `V1C001`, `V1C001P37`.
* `versionCode` stays numeric and monotonically increasing. It is derived
  from the V/C/P components with a fixed formula:

```text
versionCode = V * 100000 + C * 100 + P
```

where C is the numeric value of the three-digit cycle (C001 → 1) and P the
numeric value of the two-digit patch (P00 → 0).

| Version       | versionCode |
| ------------- | -----------:|
| V1C001P00     | 100100      |
| V1C001P01     | 100101      |
| V1C001P37     | 100137      |
| V1C002P00     | 100200      |
| V2C001P00     | 200100      |
| V2C001P03     | 200103      |

Never lower an already-published `versionCode`; the mapping above guarantees
monotonic growth for increasing V/C/P values. Never derive `versionCode` from
the literal version string in a way Android would reject.

## Version consistency

These must always match for a release:

```text
CHANGELOG.md     V1C001P37
Android versionName   V1C001P37
Git tag          v1c001p37
GitHub Release   Remot V1C001P37
APK filename     remot-v1c001p37.apk
```

The `scripts/release-check.sh` gate validates this consistency before a
production tag is created.

## Migration from legacy semantic versions

Versions `v1.0.0` and `v1.0.1` were released under legacy semver before the
V/C/P scheme was adopted:

| Legacy tag   | Released as            | Notes                                    |
| ------------ | ---------------------- | ---------------------------------------- |
| `v1.0.0`     | GitHub Release v1.0.0  | Rebrand + hardening baseline (versionCode 1). |
| `v1.0.1`     | GitHub Release v1.0.1  | CI/release workflow split + permissions. |
| `v1c001`     | first V/C/P production | Continues the same codebase as `V1C001` (versionCode 100100). |

## Package identity

As of `V2C001` the Android application ID is `com.robrion.remot` (previously
`com.remot.app`). Changing a package creates a new Android identity, so this is
**not an in-place upgrade** from the old package — installs of `com.remot.app`
must be uninstalled and re-installed fresh under `com.robrion.remot`. All
future `com.robrion.remot` releases upgrade each other normally because they
are signed with the same persistent production key.

Legacy tags and releases are **historical and must not be deleted**. The
`CHANGELOG.md` preserves them under `Legacy v1.0.0` / `Legacy v1.0.1` entries;
new V/C/P entries are inserted at the top.

## Release process

```text
local build + test + lint + release-check.sh
        ↓
push main → CI once (no release)
        ↓
select V/C/P, update CHANGELOG at top, verify versionName/versionCode
        ↓
git tag v1c001 && git push origin v1c001
        ↓
release workflow once → GitHub Release "Remot V1C001" + remot-v1c001.apk
```
