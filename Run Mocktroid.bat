@echo off
rem Builds (if needed) and launches Mocktroid using absolute paths, so nothing
rem has to be on PATH. Edit the paths below if any of these tools move.

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
set "MVN=F:\aiwork\tools\apache-maven-3.9.16\bin\mvn.cmd"
set "POM=E:\Mocktroid\Mocktroid\source\pom.xml"

rem vgmstream-cli on PATH for this window only, so sound previews work even
rem if Settings > Audio > vgmstream-cli is left on auto-detect.
set "PATH=F:\aiwork\tools\vgmstream;%JAVA_HOME%\bin;%PATH%"

call "%MVN%" -q -f "%POM%" compile javafx:run
if errorlevel 1 (
    echo.
    echo Mocktroid exited with an error - see the messages above.
    pause
)
