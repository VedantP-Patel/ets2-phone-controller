@echo off
title ETS2 Phone Cockpit - USB Wired Mode
cls
echo =========================================================
echo   ETS2 Phone Cockpit - USB Wired Mode (ADB TCP + RNDIS)
echo =========================================================
echo.
echo [*] Checking ADB device connection...
adb devices
echo.
echo [*] Configuring ADB Reverse TCP Tunnel on Port 5005...
adb reverse tcp:5005 tcp:5005
if %errorlevel% equ 0 (
    echo [OK] Loopback TCP tunnel active: Phone 127.0.0.1:5005 -^> PC 127.0.0.1:5005
    echo      In the phone app Connection dialog, enter IP: 127.0.0.1
) else (
    echo [!] ADB tunnel not available. Enable USB Debugging OR turn on USB Tethering ^(RNDIS^) for auto-discovery.
)
echo.
echo [*] Launching Control Center ^(start.bat^)...
echo =========================================================
cd /d "%~dp0\..\.."
call "%~dp0\..\..\start.bat"
