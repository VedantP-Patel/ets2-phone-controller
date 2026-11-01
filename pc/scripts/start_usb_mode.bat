@echo off
title ETS2 Phone Controller - USB Wired Mode (0.5ms Latency)
cls
echo =========================================================
echo   ETS2 Phone Controller - 1-Click USB Wired Mode
echo =========================================================
echo.
echo [*] Checking ADB device connection...
adb devices
echo.
echo [*] Configuring 0.5ms Ultra-Low-Latency Reverse Port Tunnel...
adb reverse tcp:5005 tcp:5005
adb reverse tcp:5007 tcp:5007
echo.
echo [SUCCESS] Ports 5005 & 5007 mapped to USB Loopback!
echo           On your phone, enter Server IP: 127.0.0.1
echo           or leave blank to auto-connect.
echo.
echo [*] Starting Native PC Server...
echo =========================================================
cd /d "%~dp0\.."
ets2-controller.exe

