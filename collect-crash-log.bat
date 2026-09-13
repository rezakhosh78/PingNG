@echo off
setlocal
cd /d "%~dp0"

where adb.exe >nul 2>nul
if errorlevel 1 (
  echo adb.exe was not found. Open Android Studio Terminal after installing Android SDK Platform-Tools.
  exit /b 1
)

echo Clearing old Android logs...
adb.exe logcat -c
adb.exe shell am force-stop com.pingng.android
echo Launching PingNG...
adb.exe shell monkey -p com.pingng.android -c android.intent.category.LAUNCHER 1 >nul
timeout /t 5 /nobreak >nul
adb.exe logcat -d -v threadtime > "PingNG-crash-log.txt"

echo.
echo Crash log saved to:
echo %CD%\PingNG-crash-log.txt
endlocal
