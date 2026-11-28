@echo off
setlocal EnableExtensions
title ETS2 Phone Cockpit Launcher
color 0B

set "ROOT_DIR=%~dp0"
set "PC_DIR=%ROOT_DIR%pc"
set "ANDROID_DIR=%ROOT_DIR%android"
set "BUILT_DLL=%PC_DIR%\build\ets2_phone_plugin.dll"
set "PATH_FILE=%PC_DIR%\ets2_path.txt"
set "RELEASE_BASE=https://github.com/VedantP-Patel/ets2-phone-controller/releases/latest/download"
set "RELEASE_DLL_URL=%RELEASE_BASE%/ets2_phone_plugin.dll"
set "RELEASE_APK_URL=%RELEASE_BASE%/ets2-phone-controller.apk"
set "LOCAL_APK=%ROOT_DIR%ets2-phone-controller.apk"
set "ANDROID_OUT_APK=%ANDROID_DIR%\app\build\outputs\apk\debug\app-debug.apk"

set "ETS2_BIN="
if exist "%PC_DIR%\scripts\find_ets2.ps1" (
    for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%PC_DIR%\scripts\find_ets2.ps1" -OverrideFile "%PATH_FILE%"`) do set "ETS2_BIN=%%P"
)

set "GAME_LOG=%USERPROFILE%\Documents\Euro Truck Simulator 2\game.log.txt"
if not exist "%GAME_LOG%" set "GAME_LOG=%USERPROFILE%\OneDrive\Documents\Euro Truck Simulator 2\game.log.txt"

:: Detect adb even when not on system PATH
set "ADB_EXE="
for /f "delims=" %%A in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%A"
if not defined ADB_EXE if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if exist "C:\android_sdk\platform-tools\adb.exe" set "ADB_EXE=C:\android_sdk\platform-tools\adb.exe"

set "ADB_LAUNCHED=0"

:MENU
cls
set "ETS2_EXE="
set "PLUGINS_DIR="
set "TARGET_DLL="
if defined ETS2_BIN (
    set "ETS2_EXE=%ETS2_BIN%\eurotrucks2.exe"
    set "PLUGINS_DIR=%ETS2_BIN%\plugins"
    set "TARGET_DLL=%ETS2_BIN%\plugins\ets2_phone_plugin.dll"
)

echo ========================================================================
echo               ETS2 PHONE COCKPIT  //  CONTROL CENTER
echo ========================================================================
echo.

if defined ETS2_BIN if exist "%ETS2_EXE%" (
    echo  [OK] ETS2 Folder  : %ETS2_BIN%
) else (
    echo  [!!] ETS2 Folder  : Not detected automatically ^(Press [P] to set path^)
)

:: 1. Ensure PC Plugin DLL exists (local build -> compiler -> GitHub Release fallback)
if not exist "%BUILT_DLL%" (
    echo  [..] Plugin DLL missing locally. Checking compiler / GitHub Release...
    call :COMPILE_DLL >nul 2>&1
    if not exist "%BUILT_DLL%" call :DOWNLOAD_DLL >nul 2>&1
)

if exist "%BUILT_DLL%" if defined PLUGINS_DIR (
    if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%" >nul 2>&1
    xcopy /Y /D "%BUILT_DLL%" "%PLUGINS_DIR%\" >nul 2>&1
)

if defined TARGET_DLL if exist "%TARGET_DLL%" (
    echo  [OK] Plugin DLL   : Ready in %PLUGINS_DIR%
) else if exist "%BUILT_DLL%" (
    echo  [!!] Plugin DLL   : Downloaded/Built, set ETS2 folder via [P] to install!
) else (
    echo  [!!] Plugin DLL   : Missing! Press [C] to compile or download from GitHub.
)

:: 2. Check Windows Firewall Rule
netsh advfirewall firewall show rule name="ETS2 Phone Cockpit UDP" >nul 2>&1
if %errorlevel% equ 0 (
    echo  [OK] Firewall     : UDP 5005/5006 ^& TCP 5005 Allowed
) else (
    echo  [!!] Firewall     : Not configured ^(Press [F] - Important on Wi-Fi/Hotspot!^)
)

:: 3. Check & Auto-Bind ETS2 Profile Controls (supports Steam Cloud + Documents)
set "BIND_STATUS=Press [A] to Auto-Bind Profile"
if not exist "%PC_DIR%\scripts\auto_bind_ets2.ps1" goto AFTER_BIND_CHECK
powershell -NoProfile -ExecutionPolicy Bypass -File "%PC_DIR%\scripts\auto_bind_ets2.ps1" -CheckOnly >nul 2>&1
set "BIND_CODE=%errorlevel%"
if "%BIND_CODE%"=="0" (
    set "BIND_STATUS=Auto-Bound to ETS2 Profile ^(Documents / Steam Cloud^)"
    goto AFTER_BIND_CHECK
)
if "%BIND_CODE%"=="3" (
    set "BIND_STATUS=No ETS2 profile yet ^(Launch ETS2 once, exit, press [A]^)"
    goto AFTER_BIND_CHECK
)
tasklist /FI "IMAGENAME eq eurotrucks2.exe" 2>nul | find /I "eurotrucks2.exe" >nul
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%PC_DIR%\scripts\auto_bind_ets2.ps1" >nul 2>&1
    if not errorlevel 1 set "BIND_STATUS=Auto-Bound to ETS2 Profile - Plug-and-Play"
)
:AFTER_BIND_CHECK
echo  [OK] Controls     : %BIND_STATUS%

:: 4. Check Phone Link (USB ADB / Wi-Fi / Hotspot)
set "PHONE_STATUS=Wi-Fi / Phone Hotspot / USB Tethering Ready"
if not defined ADB_EXE goto AFTER_ADB_CHECK
for /f "tokens=1,2" %%A in ('"%ADB_EXE%" devices 2^>nul ^| findstr /R /C:"device$"') do (
    set "PHONE_STATUS=USB ADB Connected ^(%%A^) - Tunnel tcp:5005 Active"
    "%ADB_EXE%" -s %%A reverse tcp:5005 tcp:5005 >nul 2>&1
    if "%ADB_LAUNCHED%"=="0" (
        "%ADB_EXE%" -s %%A shell am start -n com.ets2controller/.MainActivity >nul 2>&1
        set "ADB_LAUNCHED=1"
    )
)
:AFTER_ADB_CHECK
echo  [OK] Phone Link   : %PHONE_STATUS%

:: 5. Show clean PC IPv4 Addresses (filter out virtual VPN/Hyper-V adapters first)
echo.
echo  PC IPv4 Addresses ^(tap top bar in phone app if manual IP is needed^):
powershell -NoProfile -Command "$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' }; $real = $ips | Where-Object { $_.InterfaceAlias -notmatch 'vEthernet|Hyper-V|VMware|VirtualBox|WSL|Tailscale|WireGuard|ZeroTier' }; if (-not $real) { $real = $ips }; $real | ForEach-Object { Write-Host ('    -> ' + $_.InterfaceAlias + ': ' + $_.IPAddress) -ForegroundColor Green }"
echo.
echo ========================================================================
echo   [ENTER] Launch Euro Truck Simulator 2
echo   [A]     Auto-Bind All Controls to ETS2 Profile ^(Plug-and-Play^)
echo   [B]     Install Android App on Phone ^(GitHub APK / Wi-Fi / USB ADB^)
echo   [F]     Open Windows Firewall ^(UDP 5005/5006 + TCP 5005, All Profiles^)
echo   [C]     Rebuild or Re-Download PC Plugin DLL
echo   [P]     Set Custom ETS2 Installation Folder
echo   [L]     Check ETS2 game.log.txt for Plugin Status
echo   [R]     Refresh Status
echo   [Q]     Quit
echo ========================================================================
echo.

set "CHOICE=1"
set /p "CHOICE=Select option [Enter = Launch ETS2]: "

if /I "%CHOICE%"=="1" goto LAUNCH_GAME
if /I "%CHOICE%"=="A" goto AUTO_BIND
if /I "%CHOICE%"=="C" goto REBUILD_PLUGIN
if /I "%CHOICE%"=="B" goto SETUP_ANDROID_APP
if /I "%CHOICE%"=="F" goto FIX_FIREWALL
if /I "%CHOICE%"=="P" goto SET_ETS2_PATH
if /I "%CHOICE%"=="L" goto CHECK_LOG
if /I "%CHOICE%"=="R" goto MENU
if /I "%CHOICE%"=="Q" exit /b 0
goto LAUNCH_GAME

:COMPILE_DLL
if not exist "%PC_DIR%\build" mkdir "%PC_DIR%\build"
if exist "%BUILT_DLL%" del /F /Q "%BUILT_DLL%" >nul 2>&1

set "VCVARS="
set "VS_INST="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq delims=" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_INST=%%I"
)
if defined VS_INST if exist "%VS_INST%\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%VS_INST%\VC\Auxiliary\Build\vcvars64.bat"

if defined VCVARS (
    call "%VCVARS%" >nul 2>&1
    cl /nologo /O2 /W3 /EHsc /MT /std:c++20 /DNDEBUG /LD /I"%PC_DIR%\src" /I"%PC_DIR%\include\scssdk" "%PC_DIR%\src\ets2_phone_plugin.cpp" /Fo"%PC_DIR%\build\ets2_phone_plugin.obj" /Fe:"%BUILT_DLL%" /link ws2_32.lib iphlpapi.lib user32.lib /EXPORT:scs_input_init /EXPORT:scs_input_shutdown /EXPORT:scs_telemetry_init /EXPORT:scs_telemetry_shutdown
    if not errorlevel 1 (
        dumpbin /nologo /exports "%BUILT_DLL%" | findstr /C:"scs_input_init" >nul 2>&1
        if not errorlevel 1 exit /b 0
    )
    echo  [..] MSVC compile failed, checking MinGW g++...
)

where g++ >nul 2>&1
if %errorlevel% equ 0 (
    g++ -O2 -shared -static -static-libgcc -static-libstdc++ -std=c++20 -DNDEBUG -I"%PC_DIR%\src" -I"%PC_DIR%\include\scssdk" "%PC_DIR%\src\ets2_phone_plugin.cpp" -o "%BUILT_DLL%" -lws2_32 -liphlpapi -luser32
    exit /b %errorlevel%
)

exit /b 1

:DOWNLOAD_DLL
if not exist "%PC_DIR%\build" mkdir "%PC_DIR%\build"
echo  [..] Downloading prebuilt ets2_phone_plugin.dll from GitHub Releases...
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%RELEASE_DLL_URL%' -OutFile '%BUILT_DLL%' -UseBasicParsing; exit 0 } catch { exit 1 }"
if errorlevel 1 exit /b 1
if exist "%BUILT_DLL%" exit /b 0
exit /b 1

:REBUILD_PLUGIN
echo.
echo  Preparing ets2_phone_plugin.dll...
call :COMPILE_DLL
if errorlevel 1 (
    echo  [..] No local C++ compiler found. Downloading prebuilt DLL from GitHub...
    call :DOWNLOAD_DLL
)
if %errorlevel% equ 0 if exist "%BUILT_DLL%" (
    if defined PLUGINS_DIR (
        if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%" >nul 2>&1
        copy /Y "%BUILT_DLL%" "%PLUGINS_DIR%\" >nul
        echo  [OK] Plugin DLL ready and copied to %PLUGINS_DIR%
    ) else (
        echo  [OK] Plugin DLL saved at %BUILT_DLL%. Press [P] to set ETS2 folder.
    )
) else (
    echo  [!!] Could not compile or download DLL. Check internet connection or install VS Build Tools.
)
pause
goto MENU

:SETUP_ANDROID_APP
echo.
set "READY_APK="
if exist "%ANDROID_OUT_APK%" set "READY_APK=%ANDROID_OUT_APK%"
if not defined READY_APK if exist "%LOCAL_APK%" set "READY_APK=%LOCAL_APK%"
if not defined READY_APK if exist "%PC_DIR%\build\ets2-phone-controller.apk" set "READY_APK=%PC_DIR%\build\ets2-phone-controller.apk"

:: If Android SDK exists, offer/try local build when no APK yet
if not defined READY_APK if exist "%ANDROID_DIR%\build_apk.bat" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        echo  [..] Android SDK found. Building fresh APK...
        call "%ANDROID_DIR%\build_apk.bat"
        if exist "%ANDROID_OUT_APK%" set "READY_APK=%ANDROID_OUT_APK%"
    )
)

:: Otherwise download prebuilt APK directly from GitHub Releases
if not defined READY_APK (
    echo  [..] Downloading prebuilt Android APK from GitHub Releases...
    if not exist "%PC_DIR%\build" mkdir "%PC_DIR%\build"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%RELEASE_APK_URL%' -OutFile '%PC_DIR%\build\ets2-phone-controller.apk' -UseBasicParsing; exit 0 } catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 1 }"
    if exist "%PC_DIR%\build\ets2-phone-controller.apk" set "READY_APK=%PC_DIR%\build\ets2-phone-controller.apk"
)

if not defined READY_APK (
    echo  [!!] Could not get APK automatically.
    echo      Download ets2-phone-controller.apk from:
    echo      https://github.com/VedantP-Patel/ets2-phone-controller/releases/latest
    pause
    goto MENU
)

echo  [OK] APK Ready: %READY_APK%
if defined ADB_EXE (
    "%ADB_EXE%" devices 2>nul | findstr /R /C:"device$" >nul
    if not errorlevel 1 (
        echo  [..] Installing onto USB ADB phone...
        "%ADB_EXE%" install -r -d "%READY_APK%"
        "%ADB_EXE%" shell monkey -p com.ets2controller -c android.intent.category.LAUNCHER 1 >nul 2>&1
        pause
        goto MENU
    )
)

echo.
echo  No USB ADB phone detected. Starting Wi-Fi APK installer ^(port 8099^)...
echo  ^(Make sure port 8099 is allowed via [F] Firewall if the page fails to load^)
powershell -NoProfile -ExecutionPolicy Bypass -File "%PC_DIR%\scripts\serve_apk_wifi.ps1" -ApkPath "%READY_APK%"
pause
goto MENU

:FIX_FIREWALL
echo.
echo  Requesting Administrator permission to allow UDP 5005/5006, TCP 5005, and ETS2...
powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -Command \"Remove-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit*'' -ErrorAction SilentlyContinue; New-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit UDP'' -Direction Inbound -Protocol UDP -LocalPort 5005,5006 -Profile Any -Action Allow; New-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit TCP'' -Direction Inbound -Protocol TCP -LocalPort 5005,8099 -Profile Any -Action Allow; if (Test-Path ''%ETS2_EXE%'') { New-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit App'' -Direction Inbound -Program ''%ETS2_EXE%'' -Profile Any -Action Allow }\"'"
goto MENU

:SET_ETS2_PATH
echo.
echo  Enter your Euro Truck Simulator 2 folder path
echo  Example: C:\Program Files ^(x86^)\Steam\steamapps\common\Euro Truck Simulator 2
echo.
set "USER_ETS2="
set /p "USER_ETS2=Path: "
if not defined USER_ETS2 goto MENU
set "ETS2_USER_INPUT=%USER_ETS2%"
powershell -NoProfile -Command "$p = $env:ETS2_USER_INPUT.Trim().Trim('\"'); if ($p -match 'eurotrucks2\.exe$') { $p = Split-Path -Parent $p }; if (Test-Path (Join-Path $p 'bin\win_x64\eurotrucks2.exe')) { $p = Join-Path $p 'bin\win_x64' }; if (Test-Path (Join-Path $p 'eurotrucks2.exe')) { Set-Content -Path '%PATH_FILE%' -Value $p -Encoding UTF8; Write-Host ('[OK] Saved ETS2 path: ' + $p) -ForegroundColor Green; exit 0 } else { Write-Host '[ERROR] eurotrucks2.exe not found in that folder or bin\win_x64.' -ForegroundColor Red; exit 1 }"
set "ETS2_USER_INPUT="
for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%PC_DIR%\scripts\find_ets2.ps1" -OverrideFile "%PATH_FILE%"`) do set "ETS2_BIN=%%P"
pause
goto MENU

:CHECK_LOG
echo.
if exist "%GAME_LOG%" (
    echo  --- Plugin lines from ETS2 game.log.txt ---
    findstr /I "plugin sdk ets2_phone ETS2Phone" "%GAME_LOG%"
) else (
    echo  [!] game.log.txt not found yet. Launch ETS2 once first.
)
echo.
pause
goto MENU

:AUTO_BIND
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%PC_DIR%\scripts\auto_bind_ets2.ps1"
echo.
pause
goto MENU

:LAUNCH_GAME
if not defined ETS2_EXE (
    echo.
    echo  [ERROR] ETS2 folder not set! Press [P] in the menu to choose your ETS2 folder.
    pause
    goto MENU
)
if not exist "%ETS2_EXE%" (
    echo.
    echo  [ERROR] Could not find %ETS2_EXE%. Press [P] to update the folder.
    pause
    goto MENU
)
echo.
echo  Launching Euro Truck Simulator 2...
echo  IMPORTANT: Click OK when ETS2 asks "Request to use advanced SDK features"!
start "" /D "%ETS2_BIN%" "%ETS2_EXE%"
timeout /t 3 >nul
exit /b 0
