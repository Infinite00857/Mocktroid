@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem Builds (if needed) and launches Mocktroid on Windows.
rem
rem Needs:    a JDK 21 or newer. Maven is optional: without it the bundled
rem           wrapper (source\mvnw.cmd) downloads it on first launch.
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

rem The local file must only SET variables -- if it also runs Maven, the app
rem starts twice (once from there, once below).
if exist "Run Mocktroid.local.bat" call "Run Mocktroid.local.bat"

rem --- Java ---
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
    echo Java not found. Install a JDK 21 or newer, for example from https://adoptium.net
    echo then reopen this window, or set JAVA_HOME.
    goto :fail
)

rem --- Maven: an installed one, else the bundled wrapper (downloads Maven once, ~10 MB) ---
if not defined MVN (
    where mvn >nul 2>nul && set "MVN=mvn"
)
if not defined MVN set "MVN=%CD%\source\mvnw.cmd"


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
