@echo off
setlocal
cd /d "%~dp0"
set "AAR_SHA256=c5837bd50a048b5527d096faf88cbbca3db17048cfb7cc8eb3c05160a9815bd5"

set "AAR_VALID="
if exist "app\libs\libv2ray.aar" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; try { $p='%CD%\app\libs\libv2ray.aar'; $z=[IO.Compression.ZipFile]::OpenRead($p); $n=@($z.Entries.FullName); $z.Dispose(); if (($n -contains 'AndroidManifest.xml') -and ($n -contains 'classes.jar') -and ((Get-FileHash -Algorithm SHA256 $p).Hash -eq '%AAR_SHA256%')) { exit 0 } else { exit 1 } } catch { exit 1 }"
  if not errorlevel 1 set "AAR_VALID=1"
)

if not defined AAR_VALID (
  echo Bundled Android API 24 libv2ray.aar is missing, invalid, or has the wrong checksum.
  echo Rebuild the Xray core with native\xray\build-android.sh and retry.
  exit /b 1
)

echo Building PingNG...
call gradlew.bat assemblePlaystoreDebug
if errorlevel 1 exit /b 1

python tools\verify_root_tun_apk.py app\build\outputs\apk\playstore\debug
if errorlevel 1 exit /b 1

echo.
echo APK output folder:
echo %CD%\app\build\outputs\apk\playstore\debug
endlocal
