#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd "$(dirname "$0")" && pwd)"
PINGNG_ROOT="${PINGNG_ROOT:-$(cd "$source_dir/../../.." && pwd)}"
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" ]]; then
        ANDROID_NDK_ROOT="$sdk_root/ndk/30.0.14904198"
    fi
fi
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT or ANDROID_SDK_ROOT to an Android SDK with NDK r30}"
GO_BIN="${GO_BIN:-go}"
ndk_host="${NDK_HOST_TAG:-linux-x86_64}"
toolchain="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$ndk_host"
"$GO_BIN" version
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    case "$abi" in
        arm64-v8a) arch=arm64; target=aarch64-linux-android24; arm=;;
        armeabi-v7a) arch=arm; target=armv7a-linux-androideabi24; arm=7;;
        x86) arch=386; target=i686-linux-android24; arm=;;
        x86_64) arch=amd64; target=x86_64-linux-android24; arm=;;
    esac
    output="$PINGNG_ROOT/app/build/generated/endpointScanner/jniLibs/$abi/libendpointscanner.so"
    test -x "$toolchain/bin/$target-clang"
    mkdir -p "$(dirname "$output")"
    (cd "$source_dir" && env GOOS=android GOARCH="$arch" GOARM="$arm" CGO_ENABLED=1 \
      CC="$toolchain/bin/$target-clang" CGO_LDFLAGS='-Wl,-z,max-page-size=16384' \
      "$GO_BIN" build -trimpath -buildvcs=false -buildmode=pie \
      -ldflags='-X main.version=0.16.0-pingng' -o "$output" .)
    file "$output"
    sha256sum "$output"
done
