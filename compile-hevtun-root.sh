#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hev_dir="$project_dir/app/src/main/cpp/hev-socks5-tunnel"
abis="armeabi-v7a arm64-v8a x86 x86_64"

resolve_ndk_build() {
  local candidate=""
  for candidate in \
    "${NDK_HOME:-}" \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/30.0.14904198"; do
    [[ -n "$candidate" ]] || continue
    if [[ -x "$candidate/ndk-build" ]]; then
      printf '%s\n' "$candidate/ndk-build"
      return 0
    fi
    if [[ -x "$candidate/ndk-build.cmd" ]]; then
      printf '%s\n' "$candidate/ndk-build.cmd"
      return 0
    fi
  done

  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
    candidate="$(find "$sdk/ndk" -mindepth 2 -maxdepth 2 -type f -name ndk-build -perm -u+x -print | sort -V | tail -n 1)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  return 1
}

ndk_build="$(resolve_ndk_build)" || {
  echo "Android NDK not found. Set NDK_HOME or ANDROID_NDK_HOME to the installed NDK."
  exit 1
}
[[ -d "$hev_dir" ]] || { echo "HEV source directory is missing: $hev_dir"; exit 1; }

tmp_dir="$(mktemp -d)"
cleanup() { rm -rf "$tmp_dir"; }
trap cleanup EXIT

echo "Building standalone HEV root executable for: $abis"
for abi in $abis; do
  mkdir -p "$tmp_dir/tmp/$abi"
  TMPDIR="$tmp_dir/tmp/$abi" "$ndk_build" \
    "NDK_PROJECT_PATH=$hev_dir" \
    "APP_BUILD_SCRIPT=$hev_dir/Android.mk" \
    "APP_MODULES=hev-socks5-tunnel-bin" \
    "APP_ABI=$abi" \
    APP_PLATFORM=android-24 \
    "NDK_LIBS_OUT=$tmp_dir/libs" \
    "NDK_OUT=$tmp_dir/obj" \
    "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
done

for abi in $abis; do
  source_bin="$tmp_dir/libs/$abi/hev-socks5-tunnel-bin"
  target_dir="$project_dir/app/libs/$abi"
  target_bin="$target_dir/libhevsockstun.so"
  [[ -s "$source_bin" ]] || { echo "NDK did not produce $source_bin"; exit 1; }
  mkdir -p "$target_dir"
  install -m 0755 "$source_bin" "$target_bin"
  echo "Staged $target_bin"
done
