#!/usr/bin/env bash
# release-check.sh — Remot production release gate.
#
# Validates everything that must hold BEFORE a production tag is created:
#   * clean working tree, correct branch
#   * no secrets / APKs tracked
#   * version format (V/C/P), CHANGELOG, Android versionName/versionCode
#   * unit tests, lint, release build, APK existence, APK metadata
#
# Usage:
#   ./scripts/release-check.sh [v1c001 | V1C001 | V1C001P01] [--skip-build]
#
# If no version is given, the most recent git tag is used. If any check fails
# the script exits non-zero and prints what to fix. Run it before tagging.

set -euo pipefail

cd "$(dirname "$0")/.."   # project root

SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
  esac
done

FAILED=0
say()  { printf '%s\n' "$*"; }
ok()   { printf '  [ OK ] %s\n' "$*"; }
bad()  { printf '  [FAIL] %s\n' "$*"; FAILED=1; }

# ---------------------------------------------------------------- version
VERSION_ARG="${1:-}"
if [ -z "$VERSION_ARG" ]; then
  VERSION_ARG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
fi
[ -n "$VERSION_ARG" ] || { say "ERROR: no version argument and no git tag found."; exit 1; }

if [[ ! "$VERSION_ARG" =~ ^[Vv]([0-9])[Cc]([0-9]{3})([Pp]([0-9]{2}))?$ ]]; then
  say "ERROR: invalid version '$VERSION_ARG' (expected V1C001 or V1C001P01)."
  exit 1
fi

V_NUM="${BASH_REMATCH[1]}"
C_INT=$((10#${BASH_REMATCH[2]}))
P_INT=$((10#${BASH_REMATCH[4]:-00}))
VERSION_CODE=$((V_NUM * 100000 + C_INT * 100 + P_INT))
VERSION_NAME="${VERSION_ARG^^}"        # V1C001 / V1C001P01
TAG="${VERSION_NAME,,}"                # v1c001 / v1c001p01

say "=============================================="
say " Remot release gate"
say " Version : $VERSION_NAME  (tag $TAG)"
say " versionCode: $VERSION_CODE"
say "=============================================="

# ---------------------------------------------------------------- git state
say "Git state"
BRANCH="$(git branch --show-current)"
if [ "$BRANCH" = "main" ]; then ok "on branch main"; else bad "not on main (on '$BRANCH')"; fi

if [ -z "$(git status --porcelain)" ]; then ok "working tree clean"; else bad "working tree not clean — commit or stash first"; fi

# ---------------------------------------------------------------- secrets
say "Secrets / artifacts"
if git ls-files | grep -qiE '(^|/)\.env$|\.keystore$|\.jks$|\.p12$|\.apk$'; then
  bad "tracked secret/artifact files found:"; git ls-files | grep -iE '(^|/)\.env$|\.keystore$|\.jks$|\.p12$|\.apk$' | sed 's/^/        /'
else
  ok "no .env / keystore / APK tracked"
fi

# ---------------------------------------------------------------- changelog
say "CHANGELOG"
TOP_HEADING="$(grep -m1 '^## ' CHANGELOG.md || true)"
if [ "$TOP_HEADING" = "## $VERSION_NAME" ] || [[ "$TOP_HEADING" == "## $VERSION_NAME "* ]]; then
  ok "top entry is $VERSION_NAME"
else
  bad "top CHANGELOG entry is '$TOP_HEADING' (expected '## $VERSION_NAME')"
fi

# ---------------------------------------------------------------- android
say "Android versioning"
GRADLE_APP=android/app/build.gradle.kts
if grep -q "versionName = \"$VERSION_NAME\"" "$GRADLE_APP"; then ok "versionName = $VERSION_NAME"; else bad "versionName mismatch in $GRADLE_APP"; fi
if grep -q "versionCode = $VERSION_CODE" "$GRADLE_APP"; then ok "versionCode = $VERSION_CODE"; else bad "versionCode mismatch (expected $VERSION_CODE) in $GRADLE_APP"; fi

# ---------------------------------------------------------------- build QA
if [ "$SKIP_BUILD" = "1" ]; then
  say "Build QA skipped (--skip-build)"
else
  say "Build QA (tests + lint + release build)"
  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    bad "JAVA_HOME not set to a valid JDK 17"
  else
    GRADLE_CMD=(./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon --stacktrace)
    if [ -n "${GRADLE_JVM_ARGS:-}" ]; then
      GRADLE_CMD+=(-Dorg.gradle.jvmargs="$GRADLE_JVM_ARGS")
    fi
    ( cd android && "${GRADLE_CMD[@]}" >/tmp/remot_release_check.log 2>&1 ) \
      && ok "tests + lint + assembleRelease" \
      || { bad "gradle QA failed — see /tmp/remot_release_check.log"; tail -30 /tmp/remot_release_check.log 2>/dev/null | sed 's/^/        /'; }

    APK="$(ls android/app/build/outputs/apk/release/app-release*.apk 2>/dev/null | head -1 || true)"
    if [ -n "$APK" ]; then
      ok "release APK exists: $APK"
      AAPT2="$(ls "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt2 "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt2.exe 2>/dev/null | head -1 || true)"
      if [ -n "$AAPT2" ]; then
        BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
        if echo "$BADGING" | grep -q "versionName='$VERSION_NAME'" && echo "$BADGING" | grep -q "versionCode='$VERSION_CODE'"; then
          ok "APK metadata: versionName=$VERSION_NAME versionCode=$VERSION_CODE"
        else
          bad "APK metadata mismatch — expected versionName='$VERSION_NAME' versionCode='$VERSION_CODE'"
          echo "$BADGING" | grep -E "package:" | sed 's/^/        /'
        fi
      else
        say "  (aapt2 not found — APK metadata check skipped)"
      fi
    else
      bad "no release APK found under android/app/build/outputs/apk/release/"
    fi
  fi
fi

# ---------------------------------------------------------------- summary
say "=============================================="
if [ "$FAILED" = "0" ]; then
  say " RELEASE GATE PASSED — safe to tag $TAG"
  exit 0
else
  say " RELEASE GATE FAILED — do NOT tag $TAG"
  exit 1
fi
