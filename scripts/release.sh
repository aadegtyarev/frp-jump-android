#!/usr/bin/env bash
# Build the signed release APK as dist/frp-jump-android-<version>.apk with
# dist/frp-jump-android-<version>.apk.sha256 next to it; the version is
# versionName in app/build.gradle.kts. Publishes nothing unless --publish:
# then creates the GitHub release v<version> with both files, which the
# app's update check finds (README › Release).
#
#   scripts/release.sh                                # build and checksum only
#   scripts/release.sh --publish --notes-file NOTES.md
#
# --publish also checks: a clean tree pushed to origin, no v<version> yet,
# versionCode above the latest v* tag's, apksigner and aapt2 at hand. The
# R8 mapping goes to dist/ as well, to keep (not published).
#
# The signing key comes from FRPJUMP_RELEASE_STORE_FILE, _STORE_PASSWORD,
# _KEY_ALIAS, _KEY_PASSWORD in ~/.gradle/gradle.properties or the environment.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

PUBLISH=0
NOTES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1 ;;
    --notes-file) NOTES=${2:?--notes-file needs a file}; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

# versionName / versionCode as app/build.gradle.kts has them (at git revision $2, if given).
gradle_value() {
  local text
  if [ -n "${2:-}" ]; then text=$(git show "$2:app/build.gradle.kts"); else text=$(cat app/build.gradle.kts); fi
  printf '%s\n' "$text" | sed -n "s/^[[:space:]]*$1 = \"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" | head -1
}
VERSION=$(gradle_value versionName)
CODE=$(gradle_value versionCode)
[ -n "$VERSION" ] && [ -n "$CODE" ] || { echo "no versionName / versionCode in app/build.gradle.kts" >&2; exit 1; }
NAME="frp-jump-android-$VERSION.apk"
APKSIGNER=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
AAPT2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)

if [ "$PUBLISH" = 1 ]; then
  [ -n "$NOTES" ] && [ -s "$NOTES" ] || { echo "--publish needs --notes-file with the release notes (the app shows them)" >&2; exit 1; }
  [ -z "$(git status --porcelain)" ] || { echo "the working tree has changes; publish from a clean checkout" >&2; exit 1; }
  [ -n "$APKSIGNER" ] && [ -n "$AAPT2" ] || { echo "--publish needs apksigner and aapt2 (Android SDK build-tools)" >&2; exit 1; }
  if gh release view "v$VERSION" >/dev/null 2>&1 || git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then
    echo "v$VERSION exists already; raise versionName and versionCode" >&2
    exit 1
  fi
  # Android installs an update only with a higher versionCode.
  git fetch -q --tags origin
  LAST_TAG=$(git tag --list 'v*' --sort=-v:refname | head -1)
  if [ -n "$LAST_TAG" ]; then
    LAST_CODE=$(gradle_value versionCode "$LAST_TAG")
    [ "$CODE" -gt "${LAST_CODE:-0}" ] || { echo "versionCode $CODE is not above $LAST_CODE of $LAST_TAG" >&2; exit 1; }
  fi
  # The release points at this commit: it must be on GitHub already.
  [ -n "$(git branch -r --contains HEAD)" ] || { echo "HEAD is not pushed to origin" >&2; exit 1; }
fi

# A signed APK from an earlier build must not pass for this one.
rm -rf app/build/outputs/apk/release
./gradlew -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
  echo "no signed APK (only an unsigned one): set the FRPJUMP_RELEASE_* keys, see README › Release" >&2
  exit 1
fi

# The APK must say the version it is published as (the app checks that too).
if [ -n "$AAPT2" ]; then
  BADGING=$("$AAPT2" dump badging "$APK" | head -1)
  case "$BADGING" in
    *"versionCode='$CODE'"*"versionName='$VERSION'"*) ;;
    *) echo "the APK says $BADGING, not versionCode $CODE / versionName $VERSION" >&2; exit 1 ;;
  esac
fi

mkdir -p dist
cp "$APK" "dist/$NAME"
(cd dist && sha256sum "$NAME" > "$NAME.sha256")
# R8's mapping turns this build's stack traces back; kept, not published.
cp app/build/outputs/mapping/release/mapping.txt "dist/frp-jump-android-$VERSION-mapping.txt"
echo "dist/$NAME"
cat "dist/$NAME.sha256"

# The certificate, to check it is the release key and not another one.
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "dist/$NAME" | grep -E 'certificate (DN|SHA-256)'
fi

if [ "$PUBLISH" = 1 ]; then
  # Not a pre-release: GitHub's "latest release", which the app asks for, skips those.
  gh release create "v$VERSION" "dist/$NAME" "dist/$NAME.sha256" \
    --target "$(git rev-parse HEAD)" \
    --title "frp-jump-android $VERSION" \
    --notes-file "$NOTES"
fi
