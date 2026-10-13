@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem Builds (if needed) and launches Mocktroid on Windows.
rem
rem Needs:    a JDK 21 or newer and Maven (mvn).
rem Optional: vgmstream-cli (sound previews) and ffmpeg (WebM videos, extra
rem           export formats) -- on PATH, or vgmstream in tools\vgmstream.
rem
rem Nothing in this file is machine specific. Java and Maven are found via
rem JAVA_HOME / MVN if you set them, otherwise via PATH. To keep personal paths
rem without editing this file, create "Run Mocktroid.local.bat" next to it
rem (it is git-ignored) and set them there, for example:
rem     set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
rem     set "MVN=C:\tools\apache-maven-3.9.9\bin\mvn.cmd"
rem     set "PATH=C:\tools\vgmstream;%%PATH%%"
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

if exist "Run Mocktroid.local.bat" call "Run Mocktroid.local.bat"

rem --- Java ---
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
    echo Java not found. Install a JDK 21 or newer, for example from https://adoptium.net
    echo then reopen this window, or set JAVA_HOME.
    goto :fail
)

rem --- Maven ---
if not defined MVN set "MVN=mvn"
set "MVN_OK="
where "%MVN%" >nul 2>nul && set "MVN_OK=1"
if exist "%MVN%" set "MVN_OK=1"
if not defined MVN_OK (
    echo Maven not found. Install it from https://maven.apache.org/download.cgi and add its bin folder to PATH,
    echo or set MVN to the full path of mvn.cmd.
    goto :fail
)

rem --- Optional bundled vgmstream ---
if exist "tools\vgmstream\vgmstream-cli.exe" set "PATH=%CD%\tools\vgmstream;%PATH%"

call "%MVN%" -q -f "source\pom.xml" compile javafx:run
if errorlevel 1 goto :fail
goto :eof

:fail
echo.
echo Mocktroid could not start or exited with an error - see the messages above.
pause
exit /b 1
