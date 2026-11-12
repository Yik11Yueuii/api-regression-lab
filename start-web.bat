@echo off
setlocal EnableExtensions
title API Regression Lab - Local Web UI

rem Always run relative to this script so a double click works from any folder.
cd /d "%~dp0"

set "JAR=target\api-regression-lab-0.2.0.jar"
set "PORT=8765"
set "URL=http://127.0.0.1:%PORT%/"
set "JAVA_EXE="

rem Prefer a valid JAVA_HOME, then the common per-user JDK 21 location, then PATH.
call :try_java "%JAVA_HOME%"
if not defined JAVA_EXE call :try_java "%USERPROFILE%\.jdks\openjdk-21.0.2"
if not defined JAVA_EXE call :try_path

if not defined JAVA_EXE (
    echo [ERROR] JDK 21 was not found.
    echo         Set JAVA_HOME to a JDK 21 installation, or install JDK 21 at
    echo         %USERPROFILE%\.jdks\openjdk-21.0.2
    echo.
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo [ERROR] %JAR% was not found.
    echo         Build it first with:  mvn clean package
    echo.
    pause
    exit /b 1
)

echo Using Java: %JAVA_EXE%
echo Starting API Regression Lab web UI: %URL%
echo Keep this window open while using the page. Press Ctrl+C to stop.
echo.

rem Open the browser shortly after the server starts; the server itself stays in this window
rem so a startup failure is visible instead of disappearing with a closed console.
start "" /b cmd /c "ping -n 4 127.0.0.1 >nul & explorer %URL%"

"%JAVA_EXE%" -jar "%JAR%" --web
if errorlevel 1 (
    echo.
    echo [ERROR] The web server stopped unexpectedly. See the messages above.
    pause
)

endlocal
exit /b 0

:try_java
rem %1 is a JDK home directory. Accept it only when it is a Java 21 runtime.
if "%~1"=="" goto :eof
if not exist "%~1\bin\java.exe" goto :eof
"%~1\bin\java.exe" -version 2>&1 | findstr /R /C:"version .21\." >nul
if errorlevel 1 goto :eof
set "JAVA_EXE=%~1\bin\java.exe"
goto :eof

:try_path
rem Fall back to a Java 21 executable already on PATH.
for /f "delims=" %%J in ('where java.exe 2^>nul') do (
    "%%J" -version 2>&1 | findstr /R /C:"version .21\." >nul
    if not errorlevel 1 if not defined JAVA_EXE set "JAVA_EXE=%%J"
)
goto :eof
