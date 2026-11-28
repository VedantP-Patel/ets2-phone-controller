@echo off
setlocal EnableExtensions
title ETS2 Phone Cockpit - USB Wired Mode
cls
echo =========================================================
echo   ETS2 Phone Cockpit - USB Wired Mode (ADB TCP + RNDIS)
echo =========================================================
echo.

set "ADB_EXE="
for /f "delims=" %%A in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%A"
if not defined ADB_EXE if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if exist "C:\android_sdk\platform-tools\adb.exe" set "ADB_EXE=C:\android_sdk\platform-tools\adb.exe"

if not defined ADB_EXE (
    echo [!] ADB not found on PATH or Android SDK.
    echo     You can still use Wi-Fi, Phone Hotspot, or USB Tethering ^(RNDIS^) without ADB!
    goto LAUNCH_HUB
)

echo [*] Checking ADB device connection...
"%ADB_EXE%" devices
echo.
echo [*] Configuring ADB Reverse TCP Tunnel on Port 5005...
"%ADB_EXE%" reverse tcp:5005 tcp:5005
if %errorlevel% equ 0 (
    echo [OK] Loopback TCP tunnel active: Phone 127.0.0.1:5005 -^> PC 127.0.0.1:5005
    echo      In the phone app Connection dialog, tap "USB 127.0.0.1".
) else (
    echo [!] ADB tunnel not available. Enable USB Debugging OR turn on USB Tethering ^(RNDIS^) for auto-discovery.
)

:LAUNCH_HUB
echo.
echo [*] Launching Control Center ^(start.bat^)...
echo =========================================================
cd /d "%~dp0\..\.."
call "%~dp0\..\..\start.bat"
