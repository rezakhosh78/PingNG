#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

aar_path="$project_dir/app/libs/libv2ray.aar"
aar_sha256="c5837bd50a048b5527d096faf88cbbca3db17048cfb7cc8eb3c05160a9815bd5"

if [[ ! -s "$aar_path" ]] || ! unzip -tqq "$aar_path" >/dev/null 2>&1 \
    || [[ "$(sha256sum "$aar_path" | awk '{print $1}')" != "$aar_sha256" ]]; then
  echo "خطا: libv2ray.aar باندل‌شدهٔ API 24 موجود نیست یا checksum آن نادرست است."
  echo "هسته را با native/xray/build-android.sh بازسازی کنید."
  exit 1
fi

echo "در حال ساخت PingNG..."
./gradlew assemblePlaystoreDebug

echo "در حال بررسی فایل‌های HEV و runtimeهای native داخل APK..."
python3 "$project_dir/tools/verify_root_tun_apk.py" "$project_dir/app/build/outputs/apk/playstore/debug"

echo
echo "فایل‌های APK ساخته‌شده:"
find "$project_dir/app/build/outputs/apk" -type f -name '*.apk' -print
