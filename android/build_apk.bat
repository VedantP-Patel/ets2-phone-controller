@echo off
setlocal
cd /d "%~dp0"

echo =========================================================
echo   ETS2 Phone Controller - Android APK Build
echo =========================================================
echo.

REM Set Java Home to Android Studio's bundled JBR
if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
) else if exist "C:\Program Files\Java\jdk-25" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
)

REM Set Android SDK
set "ANDROID_HOME=C:\android_sdk"

echo Using JAVA_HOME: %JAVA_HOME%
echo Using ANDROID_HOME: %ANDROID_HOME%
echo.

REM Locate Gradle
set "GRADLE_EXEC="
for /r "C:\Users\Vedan\.gradle\wrapper\dists" %%f in (gradle.bat) do (
    if exist "%%f" (
        set "GRADLE_EXEC=%%f"
        goto :found_gradle
    )
)

:found_gradle
if "%GRADLE_EXEC%"=="" (
    echo [INFO] Please open this project folder in Android Studio:
    echo        %~dp0
    echo And click 'Build' ^> 'Build Bundle(s) / APK(s)' ^> 'Build APK(s)'.
    pause
    exit /b 0
)

echo Found Gradle: %GRADLE_EXEC%
echo Compiling Debug APK...
echo.

call "%GRADLE_EXEC%" assembleDebug
if errorlevel 1 (
    echo.
    echo [NOTE] If Gradle build requires internet sync for initial plugins,
    echo        simply open this folder in Android Studio:
    echo        %~dp0
    echo        and click 'Run' or 'Build APK'.
    pause
    exit /b 1
)

echo.
echo =========================================================
echo   APK Build Successful!
echo   Location: app\build\outputs\apk\debug\app-debug.apk
echo =========================================================
pause

