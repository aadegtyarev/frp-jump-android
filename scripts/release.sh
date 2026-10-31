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
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

VERSION=$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
[ -n "$VERSION" ] || { echo "no versionName in app/build.gradle.kts" >&2; exit 1; }
NAME="frp-jump-android-$VERSION.apk"

if [ "$PUBLISH" = 1 ]; then
  [ -n "$NOTES" ] && [ -s "$NOTES" ] || { echo "--publish needs --notes-file with the release notes (the app shows them)" >&2; exit 1; }
  [ -z "$(git status --porcelain)" ] || { echo "the working tree has changes; publish from a clean checkout" >&2; exit 1; }
  if gh release view "v$VERSION" >/dev/null 2>&1; then
    echo "release v$VERSION exists already; raise versionName and versionCode" >&2
    exit 1
  fi
fi

# A signed APK from an earlier build must not pass for this one.
rm -rf app/build/outputs/apk/release
./gradlew -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
  echo "no signed APK (only an unsigned one): set the FRPJUMP_RELEASE_* keys, see README › Release" >&2
  exit 1
fi

mkdir -p dist
cp "$APK" "dist/$NAME"
(cd dist && sha256sum "$NAME" > "$NAME.sha256")
echo "dist/$NAME"
cat "dist/$NAME.sha256"

# The certificate, to check it is the release key and not another one.
APKSIGNER=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "dist/$NAME" | grep -E 'certificate (DN|SHA-256)' || true
fi

if [ "$PUBLISH" = 1 ]; then
  # Not a pre-release: GitHub's "latest release", which the app asks for, skips those.
  gh release create "v$VERSION" "dist/$NAME" "dist/$NAME.sha256" \
    --target "$(git rev-parse HEAD)" \
    --title "frp-jump-android $VERSION" \
    --notes-file "$NOTES"
fi
