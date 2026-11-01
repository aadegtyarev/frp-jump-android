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
# --publish also checks: a clean tree on origin/main, no v<version> release
# yet (a v<version> tag only on HEAD, as when CI runs from it),
# versionCode above the latest v* tag's, apksigner and aapt2 at hand. The
# R8 mapping goes to dist/ as well, to keep (not published).
#
# The signing key comes from FRPJUMP_RELEASE_STORE_FILE, _STORE_PASSWORD,
# _KEY_ALIAS, _KEY_PASSWORD in ~/.gradle/gradle.properties or the environment;
# FRPJUMP_RELEASE_CERT_SHA256 (environment), when set, is the certificate the
# APK must be signed with.
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
  git fetch -q --tags origin
  if gh release view "v$VERSION" >/dev/null 2>&1; then
    echo "v$VERSION exists already; raise versionName and versionCode" >&2
    exit 1
  fi
  # The tag may exist only on this commit: CI publishes from a pushed tag.
  if TAG_COMMIT=$(git rev-parse -q --verify "refs/tags/v$VERSION^{commit}"); then
    [ "$TAG_COMMIT" = "$(git rev-parse HEAD)" ] || { echo "tag v$VERSION is on another commit; raise versionName and versionCode" >&2; exit 1; }
  fi
  # Android installs an update only with a higher versionCode.
  # The highest versionCode over all release tags: tag order alone ranks 0.2.0-beta1 above 0.2.0.
  LAST_CODE=0
  for tag in $(git tag --list 'v*'); do
    [ "$tag" = "v$VERSION" ] && continue
    tag_code=$(gradle_value versionCode "$tag")
    [ "${tag_code:-0}" -gt "$LAST_CODE" ] && LAST_CODE=$tag_code && LAST_TAG=$tag
  done
  [ "$CODE" -gt "$LAST_CODE" ] || { echo "versionCode $CODE is not above $LAST_CODE of $LAST_TAG" >&2; exit 1; }
  # Every installed app takes the release: only a commit merged to main.
  git merge-base --is-ancestor HEAD origin/main || { echo "HEAD is not on origin/main" >&2; exit 1; }
  if [ "${GITHUB_ACTIONS:-}" = true ] && [ -z "${FRPJUMP_RELEASE_CERT_SHA256:-}" ]; then
    echo "CI publishes only with FRPJUMP_RELEASE_CERT_SHA256 set" >&2
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

# The APK must say the version it is published as (the app checks that too).
if [ -n "$AAPT2" ]; then
  # sed reads to the end: head would close the pipe early, and pipefail ends the script.
  BADGING=$("$AAPT2" dump badging "$APK" | sed -n 1p)
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
  CERTS=$("$APKSIGNER" verify --print-certs "dist/$NAME")
  printf '%s\n' "$CERTS" | grep -E 'certificate (DN|SHA-256)'
fi
# Set (as CI does) to refuse an APK signed with any other key than this certificate's.
if [ -n "${FRPJUMP_RELEASE_CERT_SHA256:-}" ]; then
  [ -n "$APKSIGNER" ] || { echo "FRPJUMP_RELEASE_CERT_SHA256 needs apksigner" >&2; exit 1; }
  # apksigner prints "Signer #1 certificate …" or, in other versions, "V2 Signer: certificate …"; one per scheme.
  SIGNERS=$(printf '%s\n' "$CERTS" | sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' | sort -u)
  [ "$SIGNERS" = "$FRPJUMP_RELEASE_CERT_SHA256" ] || { echo "signed by $SIGNERS, not the release key $FRPJUMP_RELEASE_CERT_SHA256" >&2; exit 1; }
fi

if [ "$PUBLISH" = 1 ]; then
  # Not a pre-release: GitHub's "latest release", which the app asks for, skips those.
  gh release create "v$VERSION" "dist/$NAME" "dist/$NAME.sha256" \
    --target "$(git rev-parse HEAD)" \
    --title "frp-jump-android $VERSION" \
    --notes-file "$NOTES"
fi
