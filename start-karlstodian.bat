@echo off
setlocal
cd /d "%~dp0"
title Karlstodian

rem --- find Java 21 ---
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\jdk-21.0.13+9\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\jdk-21.0.13+9"
if not defined JAVA_HOME where java >nul 2>nul || goto nojava

echo.
echo Karlstodian is starting at http://localhost:8000  (close this window to stop it)
echo.
start "" cmd /c "timeout /t 8 >nul & start http://localhost:8000/library"
call gradlew.bat run --console=plain -q
goto end

:nojava
echo Java 21 was not found. Install Temurin 21 from https://adoptium.net/ and run this again.
:end
pause
