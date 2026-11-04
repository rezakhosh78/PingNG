@echo off
setlocal EnableExtensions
set "PROJECT_ROOT=%~dp0"
if "%PROJECT_ROOT:~-1%"=="\" set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"

set "NDK_DIR=%NDK_HOME%"
if not defined NDK_DIR set "NDK_DIR=%ANDROID_NDK_HOME%"
if not defined NDK_DIR set "NDK_DIR=%ANDROID_NDK_ROOT%"
if not defined NDK_DIR if defined ANDROID_SDK_ROOT set "NDK_DIR=%ANDROID_SDK_ROOT%\ndk\30.0.14904198"
if not defined NDK_DIR if defined ANDROID_HOME set "NDK_DIR=%ANDROID_HOME%\ndk\30.0.14904198"

if not exist "%NDK_DIR%\ndk-build.cmd" (
  echo Android NDK not found. Set NDK_HOME or ANDROID_NDK_HOME to the installed NDK.
  exit /b 1
)

rem ndk-build's legacy makefiles reject APP_PROJECT_PATH values containing spaces.
rem Map the project root to a temporary drive so the NDK sees a space-free path.
set "SUBST_DRIVE="
for %%D in (Z Y X W V U T S R Q P O N M L K J I H G F E) do if not defined SUBST_DRIVE if not exist "%%D:\" set "SUBST_DRIVE=%%D:"
if not defined SUBST_DRIVE (
  echo No free drive letter is available for the temporary NDK path mapping.
  exit /b 1
)
subst %SUBST_DRIVE% "%PROJECT_ROOT%"
if errorlevel 1 (
  echo Could not map the project to a temporary drive letter.
  exit /b 1
)
set "MAPPED_ROOT=%SUBST_DRIVE%\"
cd /d "%MAPPED_ROOT%"
if errorlevel 1 goto :fail

set "HEV_DIR=%MAPPED_ROOT%app\src\main\cpp\hev-socks5-tunnel"
if not exist "%HEV_DIR%\Android.mk" (
  echo HEV source directory is missing: %HEV_DIR%
  goto :fail
)

set "TEMP_BUILD=%MAPPED_ROOT%.tmp-hev-root-%RANDOM%-%RANDOM%"
mkdir "%TEMP_BUILD%" >nul 2>&1
if errorlevel 1 (
  echo Cannot create temporary build directory.
  goto :fail
)

echo Building standalone HEV root executable for armeabi-v7a, arm64-v8a, x86, x86_64
rem Build ABIs sequentially with private compiler temp directories. Clang's
rem Windows temporary .s names can collide when multiple ABI builds share %TEMP%.
for %%A in (armeabi-v7a arm64-v8a x86 x86_64) do (
  if not exist "%TEMP_BUILD%\tmp\%%A" mkdir "%TEMP_BUILD%\tmp\%%A"
  if not exist "%TEMP_BUILD%\tmp\%%A" goto :fail
  set "TEMP=%TEMP_BUILD%\tmp\%%A"
  set "TMP=%TEMP_BUILD%\tmp\%%A"
  set "TMPDIR=%TEMP_BUILD%\tmp\%%A"
  call "%NDK_DIR%\ndk-build.cmd" "NDK_PROJECT_PATH=%HEV_DIR%" "APP_BUILD_SCRIPT=%HEV_DIR%\Android.mk" APP_MODULES=hev-socks5-tunnel-bin "APP_ABI=%%A" APP_PLATFORM=android-24 "NDK_LIBS_OUT=%TEMP_BUILD%\libs" "NDK_OUT=%TEMP_BUILD%\obj" "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
  if errorlevel 1 goto :fail
)

for %%A in (armeabi-v7a arm64-v8a x86 x86_64) do (
  if not exist "%TEMP_BUILD%\libs\%%A\hev-socks5-tunnel-bin" (
    echo NDK did not produce %%A\hev-socks5-tunnel-bin
    goto :fail
  )
  if not exist "app\libs\%%A" mkdir "app\libs\%%A"
  copy /y "%TEMP_BUILD%\libs\%%A\hev-socks5-tunnel-bin" "app\libs\%%A\libhevsockstun.so" >nul
  if errorlevel 1 goto :fail
  echo Staged app\libs\%%A\libhevsockstun.so
)

rd /s /q "%TEMP_BUILD%"
subst %SUBST_DRIVE% /d
endlocal
exit /b 0

:fail
if defined TEMP_BUILD rd /s /q "%TEMP_BUILD%" >nul 2>&1
if defined SUBST_DRIVE subst %SUBST_DRIVE% /d >nul 2>&1
endlocal
exit /b 1
