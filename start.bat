@echo off
setlocal EnableExtensions
title ETS2 Phone Cockpit Launcher
color 0B

set "ROOT_DIR=%~dp0"
set "PC_DIR=%ROOT_DIR%pc"
set "ANDROID_DIR=%ROOT_DIR%android"
set "BUILT_DLL=%PC_DIR%\build\ets2_phone_plugin.dll"
set "ETS2_BIN="
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$cands = @('D:\Games\Euro Truck Simulator 2\bin\win_x64','C:\Program Files (x86)\Steam\steamapps\common\Euro Truck Simulator 2\bin\win_x64','C:\Program Files\Steam\steamapps\common\Euro Truck Simulator 2\bin\win_x64'); $sp = (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath; if (-not $sp) { $sp = (Get-ItemProperty -Path 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam' -ErrorAction SilentlyContinue).InstallPath }; if ($sp) { $cands += Join-Path $sp 'steamapps\common\Euro Truck Simulator 2\bin\win_x64'; $vdf = Join-Path $sp 'steamapps\libraryfolders.vdf'; if (Test-Path $vdf) { Select-String -Path $vdf -Pattern '\"path\"\s+\"([^\"]+)\"' | ForEach-Object { $lib = $_.Matches[0].Groups[1].Value -replace '\\\\','\'; $cands += Join-Path $lib 'steamapps\common\Euro Truck Simulator 2\bin\win_x64' } } }; $found = $cands | Where-Object { Test-Path (Join-Path $_ 'eurotrucks2.exe') } | Select-Object -First 1; if ($found) { Write-Output $found }"`) do set "ETS2_BIN=%%P"
if not defined ETS2_BIN set "ETS2_BIN=D:\Games\Euro Truck Simulator 2\bin\win_x64"
set "ETS2_EXE=%ETS2_BIN%\eurotrucks2.exe"
set "PLUGINS_DIR=%ETS2_BIN%\plugins"
set "TARGET_DLL=%PLUGINS_DIR%\ets2_phone_plugin.dll"

set "GAME_LOG=%USERPROFILE%\Documents\Euro Truck Simulator 2\game.log.txt"
if not exist "%GAME_LOG%" set "GAME_LOG=%USERPROFILE%\OneDrive\Documents\Euro Truck Simulator 2\game.log.txt"

:MENU
cls
echo ========================================================================
echo               ETS2 PHONE COCKPIT  //  CONTROL CENTER
echo ========================================================================
echo.

:: 1. Auto-compile C++ plugin if DLL is missing
if not exist "%BUILT_DLL%" (
    echo  [..] Plugin DLL not built yet. Auto-compiling C++ plugin...
    call :COMPILE_DLL >nul 2>&1
)

if exist "%BUILT_DLL%" (
    if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%" >nul 2>&1
    xcopy /Y /D "%BUILT_DLL%" "%PLUGINS_DIR%\" >nul 2>&1
)

if exist "%TARGET_DLL%" (
    echo  [OK] Plugin DLL   : Ready in %PLUGINS_DIR%
) else (
    echo  [!!] Plugin DLL   : Not built! Press [C] to compile and view errors.
)

:: 2. Check Windows Firewall Rule
netsh advfirewall firewall show rule name="ETS2 Phone Cockpit UDP" >nul 2>&1
if %errorlevel% equ 0 (
    echo  [OK] Firewall     : UDP Ports 5005 ^& 5006 Open
) else (
    echo  [!!] Firewall     : Blocked ^(Press [F] to allow UDP 5005/5006^)
)

:: 3. Check & Auto-Bind ETS2 Profile Controls
set "BIND_STATUS=Press [A] to Auto-Bind Profile"
if not exist "%PC_DIR%\scripts\auto_bind_ets2.ps1" goto AFTER_BIND_CHECK
findstr /S /C:"joy.steering" "%USERPROFILE%\Documents\Euro Truck Simulator 2\profiles\*.sii" "%USERPROFILE%\OneDrive\Documents\Euro Truck Simulator 2\profiles\*.sii" >nul 2>&1
if %errorlevel% equ 0 (
    set "BIND_STATUS=Auto-Bound to ETS2 Profile (Plug-and-Play)"
    goto AFTER_BIND_CHECK
)
tasklist /FI "IMAGENAME eq eurotrucks2.exe" 2>nul | find /I "eurotrucks2.exe" >nul
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%PC_DIR%\scripts\auto_bind_ets2.ps1" >nul 2>&1
    if %errorlevel% equ 0 set "BIND_STATUS=Auto-Bound to ETS2 Profile (Plug-and-Play)"
)
:AFTER_BIND_CHECK
echo  [OK] Controls     : %BIND_STATUS%

:: 4. Check Phone Link (USB ADB / USB Tethering / Wi-Fi)
set "PHONE_STATUS=Wi-Fi / USB Tethering Ready"
where adb >nul 2>&1
if %errorlevel% neq 0 goto AFTER_ADB_CHECK
for /f "tokens=1,2" %%A in ('adb devices 2^>nul ^| findstr /R /C:"device$"') do (
    set "PHONE_STATUS=USB Connected ^(%%A^) - App Launched"
    adb shell monkey -p com.ets2controller -c android.intent.category.LAUNCHER 1 >nul 2>&1
)
:AFTER_ADB_CHECK
echo  [OK] Phone Link   : %PHONE_STATUS%

:: 5. Show PC IPv4 Addresses
echo.
echo  PC IPv4 Addresses ^(tap top bar in phone app if manual IP is needed^):
powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } | ForEach-Object { Write-Host ('    -> ' + $_.InterfaceAlias + ': ' + $_.IPAddress) -ForegroundColor Green }"
echo.
echo ========================================================================
echo   [ENTER] Launch Euro Truck Simulator 2
echo   [A]     Auto-Bind All Controls to ETS2 Profile ^(Plug-and-Play^)
echo   [C]     Recompile ^& Sync PC Plugin DLL
echo   [B]     Build ^& Install Updated Android APK ^(via USB ADB^)
echo   [F]     Add Windows Firewall Rule ^(Admin UAC Prompt^)
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
if /I "%CHOICE%"=="B" goto BUILD_APK
if /I "%CHOICE%"=="F" goto FIX_FIREWALL
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
    cl /nologo /O2 /W3 /EHsc /std:c++20 /DNDEBUG /LD /I"%PC_DIR%\src" /I"%PC_DIR%\include\scssdk" "%PC_DIR%\src\ets2_phone_plugin.cpp" /Fo"%PC_DIR%\build\ets2_phone_plugin.obj" /Fe:"%BUILT_DLL%" /link ws2_32.lib iphlpapi.lib /EXPORT:scs_input_init /EXPORT:scs_input_shutdown /EXPORT:scs_telemetry_init /EXPORT:scs_telemetry_shutdown
    if not errorlevel 1 (
        dumpbin /nologo /exports "%BUILT_DLL%" | findstr /C:"scs_input_init" >nul 2>&1
        if not errorlevel 1 exit /b 0
        echo  [ERROR] DLL compiled, but scs_input_init is NOT exported!
        exit /b 1
    )
    echo  [..] MSVC compile failed, falling back to g++...
)

where g++ >nul 2>&1
if %errorlevel% equ 0 (
    g++ -O2 -shared -static -static-libgcc -static-libstdc++ -std=c++20 -DNDEBUG -I"%PC_DIR%\src" -I"%PC_DIR%\include\scssdk" "%PC_DIR%\src\ets2_phone_plugin.cpp" -o "%BUILT_DLL%" -lws2_32 -liphlpapi
    exit /b %errorlevel%
)

echo  [ERROR] No C++ compiler found. Install Visual Studio Build Tools.
exit /b 1

:REBUILD_PLUGIN
echo.
echo  Compiling ets2_phone_plugin.dll...
call :COMPILE_DLL
if %errorlevel% equ 0 (
    if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%" >nul 2>&1
    copy /Y "%BUILT_DLL%" "%PLUGINS_DIR%\" >nul
    echo  [OK] Fresh DLL compiled, exports verified, and copied to %PLUGINS_DIR%
) else (
    echo  [!!] Compilation FAILED. See error output above.
)
pause
goto MENU

:BUILD_APK
echo.
echo  Building ^& installing Android APK...
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
echo  Requesting Administrator permission to open UDP 5005 ^& 5006...
powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -Command \"Remove-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit UDP'' -ErrorAction SilentlyContinue; New-NetFirewallRule -DisplayName ''ETS2 Phone Cockpit UDP'' -Direction Inbound -Protocol UDP -LocalPort 5005,5006 -Action Allow\"'"
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