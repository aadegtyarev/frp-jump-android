#!/usr/bin/env bash
# Build upstream frpc for Android (arm64 + x86_64) into
# app/src/main/jniLibs/<abi>/libfrpc.so, so the APK ships it as a native
# library: Android only allows exec from the app's nativeLibraryDir.
#
# cgo through the NDK, not a static linux build: a pure-Go resolver reads
# /etc/resolv.conf, which Android does not have. The web admin UI is not
# needed (stub dist), and -checklinkname=0 is what frp's own wlynxg/anet
# dependency needs on Go >= 1.23.
set -euo pipefail
FRP_VERSION=${FRP_VERSION:-0.70.0}
GO_IMAGE=${GO_IMAGE:-golang:1.25.3}
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HOME/.cache/frp-jump-android}
mkdir -p "$WORK"
if [ ! -d "$WORK/frp-$FRP_VERSION" ]; then
  git clone -q --depth 1 --branch "v$FRP_VERSION" https://github.com/fatedier/frp.git "$WORK/frp-$FRP_VERSION"
fi
mkdir -p "$WORK/frp-$FRP_VERSION/web/frpc/dist"
echo '<!doctype html>' > "$WORK/frp-$FRP_VERSION/web/frpc/dist/index.html"
mkdir -p "$WORK/out" "$WORK/gocache"
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
