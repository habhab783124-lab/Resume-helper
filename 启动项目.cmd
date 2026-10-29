@echo off
setlocal enabledelayedexpansion
title ShiJie Resume Helper - Start All

cd /d "%~dp0"
set "ROOT=%~dp0"

echo ============================================================
echo   ShiJie Resume Helper  -  Start Backend + Frontend
echo ============================================================
echo.

REM ---------------------------------------------------------------- environment
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Install JDK 17+ and add it to PATH.
    pause
    exit /b 1
)
where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js not found. Install Node 18+ and add it to PATH.
    pause
    exit /b 1
)
where npm.cmd >nul 2>&1
if errorlevel 1 (
    echo [ERROR] npm.cmd not found. Install Node.js 18+.
    pause
    exit /b 1
)
if not exist "%ROOT%backend\mvnw.cmd" (
    echo [ERROR] backend\mvnw.cmd not found. Run this file from the repo root.
    pause
    exit /b 1
)
if not exist "%ROOT%frontend\package.json" (
    echo [ERROR] frontend\package.json not found. Run this file from the repo root.
    pause
    exit /b 1
)

REM ---------------------------------------------------------------- install frontend deps once
if not exist "%ROOT%frontend\node_modules\vite" (
    echo   Installing frontend dependencies, first run may take a few minutes...
    pushd "%ROOT%frontend"
    call npm.cmd install
    if errorlevel 1 (
        echo   npm install failed, retrying with npmmirror...
        call npm.cmd install --registry=https://registry.npmmirror.com
    )
    if errorlevel 1 (
        popd
        echo [ERROR] npm install failed. Check the output above.
        pause
        exit /b 1
    )
    popd
)

REM ---------------------------------------------------------------- find free ports
set /a BACK_PORT=8080
:find_back
netstat -ano | findstr "LISTENING" | findstr ":!BACK_PORT! " >nul 2>&1
if not errorlevel 1 (
    set /a BACK_PORT+=1
    if !BACK_PORT! lss 8100 goto find_back
    echo [ERROR] No free backend port between 8080 and 8099.
    pause
    exit /b 1
)

set /a FRONT_PORT=5173
:find_front
netstat -ano | findstr "LISTENING" | findstr ":!FRONT_PORT! " >nul 2>&1
if not errorlevel 1 (
    set /a FRONT_PORT+=1
    if !FRONT_PORT! lss 5200 goto find_front
    echo [ERROR] No free frontend port between 5173 and 5199.
    pause
    exit /b 1
)

echo.
echo   Backend  : http://127.0.0.1:!BACK_PORT!
echo   Frontend : http://127.0.0.1:!FRONT_PORT!/login
echo.

REM ---------------------------------------------------------------- start backend
start "ShiJie Backend" /D "%ROOT%backend" cmd /k "set SERVER_PORT=!BACK_PORT!&& mvnw.cmd spring-boot:run"
echo   Backend window started.
echo   Waiting for backend...

set /a WAIT=0
:wait_back
set /a WAIT+=1
curl --noproxy "*" -s -o nul --max-time 2 "http://127.0.0.1:!BACK_PORT!/api/jobs/sources" >nul 2>&1
if not errorlevel 1 goto back_ready
if !WAIT! geq 120 goto back_timeout
timeout /t 1 /nobreak >nul
goto wait_back

:back_timeout
echo.
echo [ERROR] Backend did not become ready within 120 seconds.
echo         Check the "ShiJie Backend" window for the Java/Maven error.
pause
exit /b 1

:back_ready
echo   Backend ready.

REM ---------------------------------------------------------------- start frontend
start "ShiJie Frontend" /D "%ROOT%frontend" cmd /k "set VITE_PROXY_TARGET=http://127.0.0.1:!BACK_PORT!&& npm.cmd run dev -- --port !FRONT_PORT! --strictPort --host 127.0.0.1"
echo   Frontend window started.
echo   Waiting for frontend...

set /a WAIT=0
:wait_front
set /a WAIT+=1
curl --noproxy "*" -s -o nul --max-time 2 "http://127.0.0.1:!FRONT_PORT!/" >nul 2>&1
if not errorlevel 1 goto front_ready
if !WAIT! geq 120 goto front_timeout
timeout /t 1 /nobreak >nul
goto wait_front

:front_timeout
echo.
echo [ERROR] Frontend did not become ready within 120 seconds.
echo         Check the "ShiJie Frontend" window for the Vite error.
pause
exit /b 1

:front_ready
echo   Frontend ready.
echo.
echo ============================================================
echo   Opening http://127.0.0.1:!FRONT_PORT!/login
echo.
echo   Keep the "ShiJie Backend" and "ShiJie Frontend" windows open.
echo   Close them to stop the app.
echo ============================================================
start "" "http://127.0.0.1:!FRONT_PORT!/login"
timeout /t 8 /nobreak >nul
exit /b 0
