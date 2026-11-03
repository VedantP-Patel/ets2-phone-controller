@echo off
setlocal enabledelayedexpansion
title ViGEmBus Driver Installer - ETS2 Phone Controller
cls

echo ========================================================================
echo   ETS2 Phone Controller - ViGEmBus Driver Setup
echo ========================================================================
echo.

REM Check if ViGEmBus is already installed
reg query "HKLM\SYSTEM\CurrentControlSet\Services\ViGEmBus" >nul 2>&1
if %errorlevel% equ 0 (
    echo [SUCCESS] ViGEmBus driver is already installed and registered on this PC!
    echo You can start the controller server anytime by double-clicking start.bat.
    echo.
    pause
    exit /b 0
)

echo [!] ViGEmBus driver was not detected on this PC.
echo     ViGEmBus is a free, open-source Windows kernel driver that allows
echo     the server to register an authentic Xbox 360 controller for ETS2.
echo.
echo [*] Attempting automated installation...
echo.

REM Step 1: Try Windows Package Manager (winget)
where winget >nul 2>&1
if %errorlevel% equ 0 (
    echo [*] Installing ViGEmBus via WinGet...
    winget install --id ViGEm.ViGEmBus -e --accept-source-agreements --accept-package-agreements
    reg query "HKLM\SYSTEM\CurrentControlSet\Services\ViGEmBus" >nul 2>&1
    if !errorlevel! equ 0 (
        echo.
        echo ========================================================================
        echo [SUCCESS] ViGEmBus driver installed successfully via WinGet!
        echo ========================================================================
        echo.
        pause
        exit /b 0
    )
    echo [-] WinGet install did not complete or was cancelled. Trying direct download...
    echo.
)

REM Step 2: Direct download of the official installer from GitHub via PowerShell
echo [*] Downloading official ViGEmBus installer from GitHub...
set "INSTALLER_PATH=%TEMP%\ViGEmBusSetup.exe"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; " ^
  "$url = 'https://github.com/nefarius/ViGEmBus/releases/download/v1.21.442.0/ViGEmBus_1.21.442_x64_x86_arm64.exe'; " ^
  "Write-Host 'Downloading: ' $url; " ^
  "Invoke-WebRequest -Uri $url -OutFile '%INSTALLER_PATH%'; " ^
  "Write-Host '[*] Download complete!' -ForegroundColor Green;"

if not exist "%INSTALLER_PATH%" (
    echo.
    echo [ERROR] Could not download installer. Please check your internet connection
    echo         or download and run the installer manually from:
    echo         https://github.com/nefarius/ViGEmBus/releases
    echo.
    pause
    exit /b 1
)

echo.
echo [*] Launching installer... (Please click 'Yes' on the Windows UAC admin prompt)
start /wait "" "%INSTALLER_PATH%"

REM Clean up installer
del "%INSTALLER_PATH%" >nul 2>&1

REM Step 3: Verify installation
reg query "HKLM\SYSTEM\CurrentControlSet\Services\ViGEmBus" >nul 2>&1
if %errorlevel% equ 0 (
    echo.
    echo ========================================================================
    echo [SUCCESS] ViGEmBus driver is now installed and active!
    echo ========================================================================
    echo You can now run start.bat to drive in Euro Truck Simulator 2.
    echo.
) else (
    echo.
    echo ========================================================================
    echo [WARNING] Driver installation was not detected. If the installer requested
    echo           a PC restart, please restart and try start.bat again.
    echo.
    echo You can also install manually anytime from:
    echo https://github.com/nefarius/ViGEmBus/releases
    echo ========================================================================
    echo.
)

pause

