#!/usr/bin/env bash
# Build upstream frpc for Android (arm64 + x86_64) into
# app/src/main/jniLibs/<abi>/libfrpc.so, so the APK ships it as a native
# library: Android only allows exec from the app's nativeLibraryDir.
#
# cgo through the NDK, not a static linux build: a pure-Go resolver reads
# /etc/resolv.conf, which Android does not have. The web admin UI is not
# needed (stub dist), and -checklinkname=0 is what frp's own wlynxg/anet
# dependency needs on Go >= 1.23.
#
# Then it lists the Go modules built into frpc, with their licenses, into
# app/src/main/assets/licenses/frpc/ (modules.json and the texts; see
# scripts/frpc-licenses/main.go), for Settings › Components and licenses;
# commit that directory with the binary's version bump. --licenses-only
# skips the build and lists the binaries already in jniLibs (after the
# same `go mod download` of the pinned frp tree).
set -euo pipefail
LICENSES_ONLY=false
[ "${1:-}" = --licenses-only ] && LICENSES_ONLY=true
# The binary goes into the signed APK: the source and the toolchain are
# pinned by commit and digest, not by tags that can be moved. Raising
# FRP_VERSION means setting FRP_COMMIT to its tag's commit as well.
FRP_VERSION=${FRP_VERSION:-0.70.0}
FRP_COMMIT=${FRP_COMMIT:-7b6e01f04f286632f0d23715aa17a3bc41234b5c}
GO_IMAGE=${GO_IMAGE:-golang:1.25.3@sha256:6d4e5e74f47db00f7f24da5f53c1b4198ae46862a47395e30477365458347bf2}
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HOME/.cache/frp-jump-android}
mkdir -p "$WORK"
if [ ! -d "$WORK/frp-$FRP_VERSION" ]; then
  git clone -q --depth 1 --branch "v$FRP_VERSION" https://github.com/fatedier/frp.git "$WORK/frp-$FRP_VERSION"
fi
HEAD_COMMIT=$(git -C "$WORK/frp-$FRP_VERSION" rev-parse HEAD 2>/dev/null) || HEAD_COMMIT=none
if [ "$HEAD_COMMIT" != "$FRP_COMMIT" ]; then
  echo "frp v$FRP_VERSION is $HEAD_COMMIT, not the pinned $FRP_COMMIT; remove $WORK/frp-$FRP_VERSION if the tag moved on purpose" >&2
  exit 1
fi
mkdir -p "$WORK/frp-$FRP_VERSION/web/frpc/dist"
echo '<!doctype html>' > "$WORK/frp-$FRP_VERSION/web/frpc/dist/index.html"
mkdir -p "$WORK/out" "$WORK/gocache"
if ! $LICENSES_ONLY; then
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GOCACHE=/gocache/build -e GOMODCACHE=/gocache/mod \
  -v "$WORK/frp-$FRP_VERSION:/src" -v "$NDK:/ndk:ro" -v "$WORK/out:/out" -v "$WORK/gocache:/gocache" \
  -w /src "$GO_IMAGE" bash -c '
set -e
TC=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
for a in arm64:aarch64 amd64:x86_64; do
  ga=${a%%:*}; ca=${a##*:}
  CGO_ENABLED=1 GOOS=android GOARCH=$ga CC=$TC/${ca}-linux-android29-clang \
    go build -buildvcs=false -trimpath -ldflags "-s -w -checklinkname=0" -tags frpc -o /out/frpc-$ga ./cmd/frpc
done'
install -D -m 755 "$WORK/out/frpc-arm64" "$ROOT/app/src/main/jniLibs/arm64-v8a/libfrpc.so"
install -D -m 755 "$WORK/out/frpc-amd64" "$ROOT/app/src/main/jniLibs/x86_64/libfrpc.so"
echo "frpc $FRP_VERSION -> app/src/main/jniLibs/{arm64-v8a,x86_64}/libfrpc.so"
fi

# The modules come from the binaries' build info, their license files from
# the module cache the build filled: `go mod download` fetches nothing new
# after a build, and the generator runs with GOPROXY=off.
LICENSES=$ROOT/app/src/main/assets/licenses/frpc
mkdir -p "$LICENSES"
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GOCACHE=/gocache/build -e GOMODCACHE=/gocache/mod \
  -e FRP_VERSION="$FRP_VERSION" \
  -v "$WORK/frp-$FRP_VERSION:/src:ro" -v "$ROOT/app/src/main/jniLibs:/jni:ro" -v "$ROOT/scripts/frpc-licenses:/tool:ro" \
  -v "$LICENSES:/outlic" -v "$WORK/gocache:/gocache" \
  -w /src "$GO_IMAGE" bash -c '
set -e
go mod download
cd /tmp
GOPROXY=off GOFLAGS=-mod=mod go run /tool/main.go -frp-version "$FRP_VERSION" -frp-src /src -out /outlic \
  /jni/arm64-v8a/libfrpc.so /jni/x86_64/libfrpc.so'
echo "frpc's modules and licenses -> app/src/main/assets/licenses/frpc/"
