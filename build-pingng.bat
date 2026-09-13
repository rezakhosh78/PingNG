@echo off
setlocal
cd /d "%~dp0"

if not exist "app\libs" mkdir "app\libs"

set "AAR_VALID="
if exist "app\libs\libv2ray.aar" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; try { $z=[IO.Compression.ZipFile]::OpenRead('%CD%\app\libs\libv2ray.aar'); $n=@($z.Entries.FullName); $z.Dispose(); if (($n -contains 'AndroidManifest.xml') -and ($n -contains 'classes.jar')) { exit 0 } else { exit 1 } } catch { exit 1 }"
  if not errorlevel 1 set "AAR_VALID=1"
)

if not defined AAR_VALID (
  echo libv2ray.aar is missing or corrupted. Downloading official copy...
  curl.exe --fail --location --retry 5 --retry-all-errors --connect-timeout 30 --max-time 300 --output "app\libs\libv2ray.aar.download" "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.9.9/libv2ray.aar"
  if errorlevel 1 (
    echo Download failed. Put a valid libv2ray.aar in app\libs and run this file again.
    exit /b 1
  )
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; try { $z=[IO.Compression.ZipFile]::OpenRead('%CD%\app\libs\libv2ray.aar.download'); $n=@($z.Entries.FullName); $z.Dispose(); if (($n -contains 'AndroidManifest.xml') -and ($n -contains 'classes.jar')) { exit 0 } else { exit 1 } } catch { exit 1 }"
  if errorlevel 1 (
    del /q "app\libs\libv2ray.aar.download" >nul 2>&1
    echo Downloaded libv2ray.aar is incomplete or invalid.
    exit /b 1
  )
  move /y "app\libs\libv2ray.aar.download" "app\libs\libv2ray.aar" >nul
)

echo Building PingNG...
call gradlew.bat assemblePlaystoreDebug
if errorlevel 1 exit /b 1

echo.
echo APK output folder:
echo %CD%\app\build\outputs\apk\playstore\debug
endlocal
