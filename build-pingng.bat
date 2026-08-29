@echo off
setlocal
cd /d "%~dp0"

if not exist "app\libs" mkdir "app\libs"

if not exist "app\libs\libv2ray.aar" (
  echo Downloading official libv2ray.aar...
  curl.exe --fail --location --retry 3 --output "app\libs\libv2ray.aar" "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.8.20/libv2ray.aar"
  if errorlevel 1 (
    echo Download failed. Put libv2ray.aar in app\libs and run this file again.
    exit /b 1
  )
)

echo Building PingNG...
call gradlew.bat assemblePlaystoreDebug
if errorlevel 1 exit /b 1

echo.
echo APK output folder:
echo %CD%\app\build\outputs\apk\playstore\debug
endlocal

