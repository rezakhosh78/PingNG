#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

aar_path="$project_dir/app/libs/libv2ray.aar"
aar_url="https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.9.9/libv2ray.aar"

mkdir -p "$project_dir/app/libs"

is_valid_aar() {
  [[ -s "$1" ]] && unzip -tqq "$1" >/dev/null 2>&1 \
    && unzip -l "$1" | grep -qE '[[:space:]]AndroidManifest\.xml$' \
    && unzip -l "$1" | grep -qE '[[:space:]]classes\.jar$'
}

if ! is_valid_aar "$aar_path"; then
  if ! command -v curl >/dev/null 2>&1; then
    echo "خطا: curl نصب نیست. فایل libv2ray.aar را داخل app/libs قرار دهید."
    exit 1
  fi
  temporary="$aar_path.download"
  echo "libv2ray.aar موجود نیست یا خراب است؛ دریافت نسخه رسمی..."
  rm -f "$temporary"
  curl --fail --location --retry 5 --retry-all-errors --connect-timeout 30 \
    --max-time 300 --output "$temporary" "$aar_url"
  if ! is_valid_aar "$temporary"; then
    rm -f "$temporary"
    echo "خطا: فایل دریافت‌شده libv2ray.aar ناقص یا نامعتبر است."
    exit 1
  fi
  mv -f "$temporary" "$aar_path"
fi

echo "در حال ساخت PingNG..."
./gradlew assemblePlaystoreDebug

echo
echo "فایل‌های APK ساخته‌شده:"
find "$project_dir/app/build/outputs/apk" -type f -name '*.apk' -print
