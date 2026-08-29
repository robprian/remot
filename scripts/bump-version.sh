#!/usr/bin/env bash
#
# bump-version.sh — bump the Remot production version (V/C/P) in one command.
#
# Every release — including small patches — MUST raise versionCode so the
# in-app auto-update prompt fires (the app compares BuildConfig.VERSION_CODE
# against the latest release's versionCode). This script makes that the
# default behaviour: it bumps the P (patch) component by default, and only
# bumps C or V when asked.
#
# Usage:
#   ./scripts/bump-version.sh            # V2C004     -> V2C004P01
#                                       # V2C004P01  -> V2C004P02
#   ./scripts/bump-version.sh --change   # V2C004P99  -> V2C005   (new change cycle, P reset)
#   ./scripts/bump-version.sh --major    # V2C999P99  -> V3C001   (new generation)
#   ./scripts/bump-version.sh --dry-run  # print the next version without editing
#
# It updates:
#   android/app/build.gradle.kts   (versionName + versionCode + comment)
#   CHANGELOG.md                   (new top entry "## VxCyyyPzz — <date>")
#
# After running, commit the changes and push main — the Build workflow builds
# the APK with the new version and the Release workflow publishes it.

set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE_APP=android/app/build.gradle.kts
MODE=patch
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --change) MODE=change ;;
    --major)  MODE=major ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- current version
CUR="$(grep -oP 'versionName = "\K[^"]+' "$GRADLE_APP" || true)"
[[ "$CUR" =~ ^V([0-9])C([0-9]{3})(P([0-9]{2}))?$ ]] || {
  echo "ERROR: cannot parse current versionName '$CUR' from $GRADLE_APP" >&2; exit 1
}
V=$((10#${BASH_REMATCH[1]}))
C=$((10#${BASH_REMATCH[2]}))
P=$((10#${BASH_REMATCH[4]:-0}))

case "$MODE" in
  patch)
    P=$((P + 1))
    if [ "$P" -gt 99 ]; then
      echo "P capped at 99 — use --change for the next change cycle." >&2
      P=99
    fi
    ;;
  change)
    C=$((C + 1)); P=0
    if [ "$C" -gt 999 ]; then
      echo "C capped at 999 — use --major for the next generation." >&2
      C=999
    fi
    ;;
  major)
    V=$((V + 1)); C=1; P=0
    ;;
esac

VER_NUM="V${V}C$(printf '%03d' "$C")"
if [ "$P" -gt 0 ]; then VER_NUM="${VER_NUM}P$(printf '%02d' "$P")"; fi
VER_CODE=$((V * 100000 + C * 100 + P))
TAG="${VER_NUM,,}"

echo "current : $CUR  (versionCode $(grep -oP 'versionCode = \K[0-9]+' "$GRADLE_APP" || true))"
echo "new     : $VER_NUM  (versionCode $VER_CODE, tag $TAG)"
[ "$DRY_RUN" = "1" ] && { echo "(dry run — nothing written)"; exit 0; }

# ---------------------------------------------------------------- build.gradle.kts
python3 - "$GRADLE_APP" "$VER_NUM" "$VER_CODE" <<'PY'
import re, sys
path, ver, code = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
s = re.sub(r'// versionCode = V\*100000 \+ C\*100 \+ P  →  V[^\n]*',
           f'// versionCode = V*100000 + C*100 + P  →  {ver} = {code}', s)
s = re.sub(r'versionCode = \d+', f'versionCode = {code}', s, count=1)
s = re.sub(r'versionName = "[^"]+"', f'versionName = "{ver}"', s, count=1)
open(path, 'w').write(s)
PY

# ---------------------------------------------------------------- CHANGELOG.md
TODAY="$(date +%F)"
python3 - CHANGELOG.md "$VER_NUM" "$TODAY" "$TAG" "$VER_CODE" <<'PY'
import sys
path, ver, today, tag, code = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
s = open(path).read()
marker = "## " + ver
if marker in s:
    print(f"CHANGELOG already has '{marker}' — leaving it untouched.", file=sys.stderr)
    sys.exit(0)
entry = f"""## {ver} — {today}

### Summary

Patch release of Remot. The full change description for this version lives in
this entry (edit before committing).

### Changed

- Bumped production version to **{ver}** (versionCode {code}).

---
"""
# Insert after the header block (first "---" line) so it becomes the top entry.
lines = s.split('\n')
# find the first line that is exactly '---' (end of the intro header)
idx = next((i for i, l in enumerate(lines) if l.strip() == '---'), 1)
head = '\n'.join(lines[:idx + 1])
rest = '\n'.join(lines[idx + 1:]).lstrip('\n')
open(path, 'w').write(head + '\n\n' + entry + rest + '\n')
PY

echo
echo "Done. Next:"
echo "  1. Edit the new top CHANGELOG entry with the actual notes."
echo "  2. git add -A && git commit -m 'release(android): bump to $VER_NUM' && git push origin main"
echo "  (Build + rolling Release will publish tag $TAG and remot-${VER_NUM}.apk)"