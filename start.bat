@echo off
setlocal
cd /d "%~dp0pc"

if exist "ets2-controller-updated.exe" (
    move /y "ets2-controller-updated.exe" "ets2-controller.exe" >nul 2>&1
)

REM Pre-flight Check: Ensure ViGEmBus kernel driver is installed
reg query "HKLM\SYSTEM\CurrentControlSet\Services\ViGEmBus" >nul 2>&1
if errorlevel 1 (
    cls
    echo ========================================================================
    echo   [ACTION REQUIRED] ViGEmBus Driver Not Found!
    echo ========================================================================
    echo.
    echo   ETS2 Phone Controller requires the free ViGEmBus driver to
    echo   emulate a genuine Xbox 360 controller in Windows.
    echo.
    echo   Starting automated installer now...
    echo ========================================================================
    echo.
    call "%~dp0install_vigembus.bat"
    
    reg query "HKLM\SYSTEM\CurrentControlSet\Services\ViGEmBus" >nul 2>&1
    if errorlevel 1 (
        echo.
        echo [ERROR] ViGEmBus driver installation was not completed.
        echo         Please run install_vigembus.bat or install manually from:
        echo         https://github.com/nefarius/ViGEmBus/releases
        echo.
        pause
        exit /b 1
    )
    cls
)

if not exist "ets2-controller.exe" (
    echo [ERROR] ets2-controller.exe not found. Building now...
    call build.bat
)

echo =========================================================
echo   Starting ETS2 Phone Controller Native Server...
echo =========================================================
echo.
ets2-controller.exe

pause
