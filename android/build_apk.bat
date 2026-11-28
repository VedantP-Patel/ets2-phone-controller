@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo =========================================================
echo   ETS2 Phone Controller - Android APK Build ^& Install
echo =========================================================
echo.

REM Auto-detect JDK (Android Studio JBR, Microsoft/Adoptium/Oracle JDK)
if not defined JAVA_HOME (
    for %%D in (
        "C:\Program Files\Android\Android Studio\jbr"
        "C:\Program Files\Java\jdk-25"
        "C:\Program Files\Java\jdk-21"
        "C:\Program Files\Java\jdk-17"
    ) do (
        if exist "%%~D\bin\java.exe" if not defined JAVA_HOME set "JAVA_HOME=%%~D"
    )
)
if not defined JAVA_HOME (
    for /f "delims=" %%P in ('dir /b /ad "C:\Program Files\Eclipse Adoptium\jdk-*" "C:\Program Files\Microsoft\jdk-*" 2^>nul') do (
        if exist "C:\Program Files\Eclipse Adoptium\%%P\bin\java.exe" if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\%%P"
        if exist "C:\Program Files\Microsoft\%%P\bin\java.exe" if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Microsoft\%%P"
    )
)

REM Auto-detect Android SDK
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else if exist "C:\android_sdk" (
        set "ANDROID_HOME=C:\android_sdk"
    ) else if exist "%USERPROFILE%\Android\Sdk" (
        set "ANDROID_HOME=%USERPROFILE%\Android\Sdk"
    )
)

if not defined ANDROID_HOME (
    echo [ERROR] Android SDK not found! Install Android Studio or download the
    echo         ready-made APK via start.bat [B] instead of compiling locally.
    exit /b 1
)

if not exist "local.properties" (
    powershell -NoProfile -Command "$p = '%ANDROID_HOME%'.Replace('\','\\'); Set-Content -Path 'local.properties' -Value ('sdk.dir=' + $p) -Encoding ASCII"
)

if defined JAVA_HOME echo Using JAVA_HOME   : %JAVA_HOME%
echo Using ANDROID_HOME: %ANDROID_HOME%
echo.

echo Compiling Debug APK using Gradle Wrapper...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed! Check the Gradle output above.
    exit /b 1
)

echo.
echo =========================================================
echo   APK Build Successful!
echo   Location: app\build\outputs\apk\debug\app-debug.apk
echo =========================================================
echo.

set "ADB_EXE="
if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_EXE (
    for /f "delims=" %%A in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%A"
)

if defined ADB_EXE (
    "%ADB_EXE%" devices 2>nul | findstr /R /C:"device$" >nul
    if not errorlevel 1 (
        echo Installing updated APK onto connected phone...
        "%ADB_EXE%" install -r -d "app\build\outputs\apk\debug\app-debug.apk"
        "%ADB_EXE%" shell monkey -p com.ets2controller -c android.intent.category.LAUNCHER 1 >nul 2>&1
    ) else (
        echo [INFO] No USB ADB phone detected. APK built at app\build\outputs\apk\debug\app-debug.apk
    )
) else (
    echo [INFO] Copy app\build\outputs\apk\debug\app-debug.apk to your phone to install.
)

exit /b 0
