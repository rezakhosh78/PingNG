#!/usr/bin/env bash
set -euo pipefail
# Requires Go 1.25+ and an Android NDK with LLVM clang (tested with r26c).
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to your Android NDK directory}"
GO_BIN="${GO_BIN:-go}"
native_root="$(cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(cd -- "$native_root/../.." && pwd)"
ndk_host="${NDK_HOST_TAG:-linux-x86_64}"
toolchain="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$ndk_host"
cd "$native_root/android-wrapper"
export GOOS=android CGO_ENABLED=1 GOARM=7
export CGO_LDFLAGS="-Wl,-soname=libwg-go.so -Wl,-Bsymbolic -Wl,--version-script=$PWD/pingng-exports.map -Wl,-z,max-page-size=16384"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    case "$abi" in
        arm64-v8a) export GOARCH=arm64; target=aarch64-linux-android21;;
        armeabi-v7a) export GOARCH=arm; target=armv7a-linux-androideabi21;;
        x86) export GOARCH=386; target=i686-linux-android21;;
        x86_64) export GOARCH=amd64; target=x86_64-linux-android21;;
    esac
    export CC="$toolchain/bin/$target-clang"
    "$GO_BIN" build -tags linux -ldflags='-buildid=' -trimpath -buildvcs=false \
        -buildmode=c-shared -o "$project_root/app/libs/$abi/libwg-go.so" .
    printf 'Built %s\n' "$abi"
done
