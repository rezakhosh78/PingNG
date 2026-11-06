#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_url="https://github.com/patterniha/AndroidLibXrayLite.git"
source_commit="faad7a00ee2934ac101a677681523f6375b27f86"
gomobile_version="v0.0.0-20261011034528-197770444867"
ndk_version="29.0.14206865"

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_SDK_ROOT:=$ANDROID_HOME}"
: "${ANDROID_NDK_HOME:=${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/$ndk_version}}"
: "${ANDROID_NDK_ROOT:=$ANDROID_NDK_HOME}"
export ANDROID_HOME ANDROID_SDK_ROOT ANDROID_NDK_HOME ANDROID_NDK_ROOT

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v go >/dev/null || { echo "Go 1.27 or newer is required" >&2; exit 1; }
command -v gomobile >/dev/null || { echo "Install gomobile@$gomobile_version first" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
[[ -d "$ANDROID_NDK_HOME" ]] || { echo "Android NDK $ndk_version not found at $ANDROID_NDK_HOME" >&2; exit 1; }

aar_path="$project_dir/app/libs/libv2ray.aar"
[[ -s "$aar_path" ]] || { echo "Bundled libv2ray.aar is required to retain the pinned geo assets" >&2; exit 1; }

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
source_dir="$work_dir/AndroidLibXrayLite"
git clone --filter=blob:none --no-checkout "$source_url" "$source_dir"
git -C "$source_dir" fetch --depth 1 origin "$source_commit"
git -C "$source_dir" checkout --detach "$source_commit"

mkdir -p "$source_dir/assets"
unzip -jo "$aar_path" 'assets/*' -d "$source_dir/assets" >/dev/null

cd "$source_dir"
gomobile init
gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o "$work_dir/libv2ray-api24.aar" ./

python3 - "$work_dir/libv2ray-api24.aar" <<'PY'
import re
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as aar:
    manifest = aar.read("AndroidManifest.xml").decode("utf-8")
    if not re.search(r'android:minSdkVersion\s*=\s*["\']24["\']', manifest):
        raise SystemExit("The generated Xray AAR does not declare minSdkVersion 24")
    if "classes.jar" not in aar.namelist():
        raise SystemExit("The generated Xray AAR is missing classes.jar")
PY

install -m 0644 "$work_dir/libv2ray-api24.aar" "$aar_path"
sha256sum "$aar_path"
