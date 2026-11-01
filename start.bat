@echo off
setlocal
cd /d "%~dp0pc"

if exist "ets2-controller-updated.exe" (
    move /y "ets2-controller-updated.exe" "ets2-controller.exe" >nul 2>&1
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

