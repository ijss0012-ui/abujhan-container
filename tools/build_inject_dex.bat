@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set SDK=%LOCALAPPDATA%\Android\Sdk
set D8=%SDK%\build-tools\35.0.0\d8.bat
set ANDROID_JAR=%SDK%\platforms\android-35\android.jar

set DEXOUT=build\inject_dex

if not exist "%ANDROID_JAR%" (
  echo [!] android.jar not found at %ANDROID_JAR%
  exit /b 1
)

echo [1/2] gradle compile inject module ...
call gradlew.bat --no-daemon :inject:compileDebugJavaWithJavac
if errorlevel 1 goto :fail

set CLASSES=inject\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes
if not exist "%CLASSES%\com\container\inject" (
  echo [!] compiled classes not found in %CLASSES%
  goto :fail
)

if exist "%DEXOUT%" rmdir /s /q "%DEXOUT%"
mkdir "%DEXOUT%"

set CFILES=
for /r "%CLASSES%\com\container\inject" %%f in (*.class) do set CFILES=!CFILES! "%%f"

echo [2/2] d8 ...
call "%D8%" --min-api 26 --lib "%ANDROID_JAR%" --output "%DEXOUT%" !CFILES!
if errorlevel 1 goto :fail

if not exist "app\src\main\assets" mkdir "app\src\main\assets"
copy /y "%DEXOUT%\classes.dex" "app\src\main\assets\location_inject.dex" >nul
echo.
echo DONE -^> app\src\main\assets\location_inject.dex
exit /b 0

:fail
echo.
echo *** BUILD FAILED ***
exit /b 1
