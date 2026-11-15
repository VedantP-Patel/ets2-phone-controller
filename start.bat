@echo off
setlocal EnableExtensions
title ETS2 Phone Cockpit Launcher
color 0B

set "ROOT_DIR=%~dp0"
set "PC_DIR=%ROOT_DIR%pc"
set "ANDROID_DIR=%ROOT_DIR%android"
set "BUILT_DLL=%PC_DIR%\build\ets2_phone_plugin.dll"
set "ETS2_BIN=D:\Games\Euro Truck Simulator 2\bin\win_x64"
set "ETS2_EXE=%ETS2_BIN%\eurotrucks2.exe"
set "PLUGINS_DIR=%ETS2_BIN%\plugins"
set "TARGET_DLL=%PLUGINS_DIR%\ets2_phone_plugin.dll"
set "GAME_LOG=%USERPROFILE%\Documents\Euro Truck Simulator 2\game.log.txt"

:MENU
cls
echo ========================================================================
echo               ETS2 PHONE COCKPIT  //  CONTROL CENTER
echo ========================================================================
echo.

:: 1. Auto-compile C++ plugin if DLL is missing
if not exist "%BUILT_DLL%" (
    echo  [..] Plugin DLL not built yet. Auto-compiling C++ plugin...
    call :BUILD_PLUGIN_INTERNAL
)

if exist "%BUILT_DLL%" (
    if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%" >nul 2>&1
    xcopy /Y /D "%BUILT_DLL%" "%PLUGINS_DIR%\" >nul 2>&1
)

if exist "%TARGET_DLL%" (
    echo  [OK] Plugin DLL   : Ready in D:\Games\Euro Truck Simulator 2\bin\win_x64\plugins
) else (
    echo  [!!] Plugin DLL   : Build failed! Press [C] to view compiler output.
)

:: 2. Check Windows Firewall Rule
netsh advfirewall firewall show rule name="ETS2 Phone Cockpit UDP" >nul 2>&1
if %errorlevel% equ 0 (
    echo  [OK] Firewall     : UDP Ports 5005 ^& 5006 Open
) else (
    echo  [!!] Firewall     : Blocked ^(Press [F] to allow UDP 5005/5006^)
)

:: 3. Check Phone Link (USB ADB / USB Tethering / Wi-Fi)
set "PHONE_STATUS=Wi-Fi / USB Tethering Ready"
where adb >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=1,2" %%A in ('adb devices 2^>nul ^| findstr /R /C:"device$"') do (
        set "PHONE_STATUS=USB Connected ^(%%A^) - App Launched"
        adb shell monkey -p com.ets2controller -c android.intent.category.LAUNCHER 1 >nul 2>&1
    )
)
echo  [OK] Phone Link   : %PHONE_STATUS%

:: 4. Show PC IPv4 Addresses (copy any IP into the app's top bar if needed)
echo.
echo  PC IPv4 Addresses ^(tap top bar in phone app if manual IP is needed^):
powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } | ForEach-Object { Write-Host ('    -> ' + $_.InterfaceAlias + ': ' + $_.IPAddress) -ForegroundColor Green }"
echo.
echo ========================================================================
echo   [ENTER] Launch Euro Truck Simulator 2
echo   [C]     Recompile & Sync PC Plugin DLL
echo   [B]     Build & Install Updated Android APK ^(via USB ADB^)
echo   [F]     Add Windows Firewall Rule ^(Admin UAC Prompt^)
echo   [L]     Check ETS2 game.log.txt for Plugin Status
echo   [R]     Refresh Status
echo   [Q]     Quit
echo ========================================================================
echo.

set "CHOICE=1"
set /p "CHOICE=Select option [Enter = Launch ETS2]: "

if /I "%CHOICE%"=="1" goto LAUNCH_GAME
if /I "%CHOICE%"=="C" goto REBUILD_PLUGIN
if /I "%CHOICE%"=="B" goto BUILD_APK
if /I "%CHOICE%"=="F" goto FIX_FIREWALL
if /I "%CHOICE%"=="L" goto CHECK_LOG
if /I "%CHOICE%"=="R" goto MENU
if /I "%CHOICE%"=="Q" exit /b 0
goto LAUNCH_GAME

:BUILD_PLUGIN_INTERNAL
powershell -NoProfile -Command ^
  "$vs = & '${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe' -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath;" ^
  "if (-not $vs) { exit 1 };" ^
  "cmd /c '\"' + $vs + '\VC\Auxiliary\Build\vcvars64.bat\" >nul && cl /nologo /O2 /W3 /EHsc /std:c++20 /DNDEBUG /LD /I\"%PC_DIR%\src\" /I\"%PC_DIR%\include\scssdk\" \"%PC_DIR%\src\ets2_phone_plugin.cpp\" /Fe:\"%BUILT_DLL%\" /link ws2_32.lib iphlpapi.lib'" >nul 2>&1
exit /b %errorlevel%

:REBUILD_PLUGIN
echo.
echo  Compiling ets2_phone_plugin.dll...
if not exist "%PC_DIR%\build" mkdir "%PC_DIR%\build"
powershell -NoProfile -Command ^
  "$vs = & '${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe' -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath;" ^
  "cmd /c '\"' + $vs + '\VC\Auxiliary\Build\vcvars64.bat\" && cl /nologo /O2 /W3 /EHsc /std:c++20 /DNDEBUG /LD /I\"%PC_DIR%\src\" /I\"%PC_DIR%\include\scssdk\" \"%PC_DIR%\src\ets2_phone_plugin.cpp\" /Fe:\"%BUILT_DLL%\" /link ws2_32.lib iphlpapi.lib'"
if exist "%BUILT_DLL%" (
    if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%" >nul 2>&1
    copy /Y "%BUILT_DLL%" "%PLUGINS_DIR%\" >nul
    echo  [OK] DLL compiled and copied to %PLUGINS_DIR%
)
pause
goto MENU

:BUILD_APK
echo.
echo  Building & installing Android APK...
pushd "%ANDROID_DIR%"
call gradlew.bat assembleDebug
if %errorlevel% equ 0 (
    adb install -r "app\build\outputs\apk\debug\app-debug.apk"
    adb shell monkey -p com.ets2controller -c android.intent.category.LAUNCHER 1 >nul 2>&1
)
popd
pause
goto MENU

:FIX_FIREWALL
echo.
echo  Requesting Administrator permission to open UDP 5005 & 5006...
powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -Command \"Remove-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit UDP'' -ErrorAction SilentlyContinue; New-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit UDP'' -Direction Inbound -Protocol UDP -LocalPort 5005,5006 -Action Allow\"'"
goto MENU

:CHECK_LOG
echo.
if exist "%GAME_LOG%" (
    echo  --- Plugin lines from ETS2 game.log.txt ---
    findstr /I "plugin sdk ets2_phone" "%GAME_LOG%"
) else (
    echo  [!] game.log.txt not found yet. Launch ETS2 once first.
)
echo.
pause
goto MENU

:LAUNCH_GAME
if not exist "%ETS2_EXE%" (
    echo.
    echo  [ERROR] Could not find %ETS2_EXE%
    pause
    goto MENU
)
echo.
echo  Launching Euro Truck Simulator 2...
echo  IMPORTANT: Click OK when ETS2 asks "Request to use advanced SDK features"!
start "" /D "%ETS2_BIN%" "%ETS2_EXE%"
timeout /t 3 >nul
exit /b 0