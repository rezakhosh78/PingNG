#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

aar_path="$project_dir/app/libs/libv2ray.aar"
aar_url="https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.8.20/libv2ray.aar"

mkdir -p "$project_dir/app/libs"

if [[ ! -f "$aar_path" ]]; then
  if ! command -v curl >/dev/null 2>&1; then
    echo "خطا: curl نصب نیست. فایل libv2ray.aar را داخل app/libs قرار دهید."
    exit 1
  fi
  echo "در حال دریافت libv2ray.aar رسمی..."
  curl --fail --location --retry 3 --output "$aar_path" "$aar_url"
fi

if [[ ! -s "$aar_path" ]]; then
  echo "خطا: app/libs/libv2ray.aar خالی یا نامعتبر است."
  exit 1
fi

echo "در حال ساخت PingNG..."
./gradlew assemblePlaystoreDebug

echo
echo "فایل‌های APK ساخته‌شده:"
find "$project_dir/app/build/outputs/apk" -type f -name '*.apk' -print

