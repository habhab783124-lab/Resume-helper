@echo off
REM ============================================================================
REM  ShiJie Resume Helper - demo launcher  (拾阶求职工作台 演示启动器)
REM
REM  Why this file is ASCII-only:
REM    cmd.exe reads a .cmd file using the console code page. Non-ASCII text can
REM    render as garbage or even break parsing depending on the machine locale.
REM    A demo launcher must not fail for cosmetic reasons, so all Chinese
REM    instructions live in the manual (演示操作手册.md) instead.
REM
REM  Expected layout (run this file from the folder that contains it):
REM    <this file>
REM    app\resume-helper-backend-0.1.0-SNAPSHOT.jar
REM    app\webapp\index.html                      <- built frontend
REM    jre\bin\java.exe                           <- optional portable JRE 17
REM
REM  What it does:
REM    1. picks the bundled JRE if present, otherwise uses java from PATH
REM    2. finds a free TCP port starting at 8080
REM    3. starts the backend (which also serves the frontend - single process)
REM    4. waits until the API answers, then opens the browser
REM ============================================================================

setlocal enabledelayedexpansion
title ShiJie Resume Helper - Demo

cd /d "%~dp0"

echo ============================================================
echo   ShiJie Resume Helper  -  Demo Launcher
echo ============================================================
echo.

REM ---------------------------------------------------------------- 1. java
set "JAVA="
if exist "%~dp0jre\bin\java.exe" set "JAVA=%~dp0jre\bin\java.exe"
if not defined JAVA (
    where java >nul 2>&1
    if not errorlevel 1 set "JAVA=java"
)
if not defined JAVA (
    echo [ERROR] Java not found.
    echo.
    echo   Fix: put a portable JRE 17 under jre\  so that jre\bin\java.exe exists,
    echo   or install Java 17+ on this machine.
    echo.
    pause
    exit /b 1
)

if not exist "%~dp0app\resume-helper-backend-0.1.0-SNAPSHOT.jar" (
    echo [ERROR] Backend jar not found:
    echo         app\resume-helper-backend-0.1.0-SNAPSHOT.jar
    echo.
    pause
    exit /b 1
)

REM ---------------------------------------------------------------- 2. port
set /a PORT=8080
:findport
netstat -an | findstr ":%PORT% " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
    set /a PORT+=1
    if !PORT! lss 8100 goto findport
)

echo   Java   : %JAVA%
echo   Port   : %PORT%
echo.
echo   Starting service in a separate window (minimized).
echo   DO NOT CLOSE that window while the demo is running.
echo.

REM ---------------------------------------------------------------- 3. start
cd /d "%~dp0app"
start "ShiJie Service - keep this window open" /min "%JAVA%" -jar "resume-helper-backend-0.1.0-SNAPSHOT.jar" --server.port=%PORT%

REM ---------------------------------------------------------------- 4. wait + open
echo   Waiting for the service to become ready ...
set /a TRIES=0
:waitready
set /a TRIES+=1
curl -s -o nul --max-time 2 "http://localhost:%PORT%/api/jobs/sources" >nul 2>&1
if not errorlevel 1 goto ready
if !TRIES! geq 30 goto timeout
timeout /t 1 /nobreak >nul
goto waitready

:timeout
echo.
echo [WARN] Service did not answer within 30 seconds.
echo        Check the minimized "ShiJie Service" window for error messages.
echo.
pause
exit /b 1

:ready
echo   Ready.
echo.
start "" "http://localhost:%PORT%/"

echo ============================================================
echo   Browser opened:  http://localhost:%PORT%/
echo.
echo   To stop the demo, close the minimized service window.
echo   You can close THIS window now.
echo ============================================================
timeout /t 10 /nobreak >nul
exit /b 0
