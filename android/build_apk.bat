@echo off
setlocal
cd /d "%~dp0"

echo =========================================================
echo   ETS2 Phone Controller - Android APK Build ^& Install
echo =========================================================
echo.

REM Set Java Home to Android Studio's bundled JBR or installed JDK
if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
) else if exist "C:\Program Files\Java\jdk-25" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
) else if exist "C:\Program Files\Java\jdk-21" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-21"
) else if exist "C:\Program Files\Java\jdk-17" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-17"
)

REM Set Android SDK (checks C:\android_sdk and default AppData location)
if exist "C:\android_sdk" (
    set "ANDROID_HOME=C:\android_sdk"
) else if exist "%LOCALAPPDATA%\Android\Sdk" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)

echo Using JAVA_HOME: %JAVA_HOME%
echo Using ANDROID_HOME: %ANDROID_HOME%
echo.

echo Compiling Debug APK using Gradle Wrapper...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed! Check the Gradle output above.
    pause
    exit /b 1
)

echo.
echo =========================================================
echo   APK Build Successful!
echo   Location: app\build\outputs\apk\debug\app-debug.apk
echo =========================================================
echo.

REM Automatically install onto connected phone if ADB is available
if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    echo Installing updated APK onto connected phone...
    "%ANDROID_HOME%\platform-tools\adb.exe" install -r -d "app\build\outputs\apk\debug\app-debug.apk"
) else (
    where adb >nul 2>nul
    if %errorlevel%==0 (
        echo Installing updated APK onto connected phone...
        adb install -r -d "app\build\outputs\apk\debug\app-debug.apk"
    ) else (
        echo [INFO] Copy app\build\outputs\apk\debug\app-debug.apk to your phone to install.
    )
)

echo.
pause
