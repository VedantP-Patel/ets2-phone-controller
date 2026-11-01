@echo off
setlocal
cd /d "%~dp0"

echo =========================================================
echo   Compiling ETS2 Phone Controller Native C++ Server...
echo =========================================================
echo.

mingw32-make
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed! Check compiler output above.
    pause
    exit /b 1
)

echo.
echo [DONE] Executable ready: %~dp0ets2-controller.exe
pause

