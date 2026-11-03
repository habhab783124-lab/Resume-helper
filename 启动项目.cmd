@echo off
setlocal enabledelayedexpansion
title ShiJie Resume Helper - Start All

cd /d "%~dp0"
set "ROOT=%~dp0"
set "LOG_DIR=%ROOT%logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM Prefer project-local tools when installed; no system PATH changes needed.
for /d %%D in ("%ROOT%.runtime\ffmpeg-*") do if exist "%%~fD\bin\ffmpeg.exe" (
    set "FFMPEG_PATH=%%~fD\bin\ffmpeg.exe"
    set "FFPROBE_PATH=%%~fD\bin\ffprobe.exe"
)
if exist "%ROOT%.runtime\maven" set "MAVEN_USER_HOME=%ROOT%.runtime\maven"

echo ============================================================
echo   ShiJie Resume Helper  -  Start Backend + Frontend
echo ============================================================
echo.

REM ---------------------------------------------------------------- environment
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\ensure-java.ps1" -ProjectRoot "%CD%"
if errorlevel 1 (
    pause
    exit /b 1
)
call "%ROOT%.runtime\java-env.cmd"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\ensure-node.ps1" -ProjectRoot "%CD%"
if errorlevel 1 (
    pause
    exit /b 1
)
call "%ROOT%.runtime\node-env.cmd"
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

REM ---------------------------------------------------------------- install speech tools once
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\ensure-speech-tools.ps1" -ProjectRoot "%CD%"
if errorlevel 1 (
    pause
    exit /b 1
)
call "%ROOT%.runtime\speech-tools.cmd"

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
set "SERVER_PORT=!BACK_PORT!"
if exist "%LOG_DIR%\backend.exit" del /q "%LOG_DIR%\backend.exit"
start "ShiJie Backend" /D "%ROOT%backend" "%ComSpec%" /d /k call "%ROOT%tools\run-backend.cmd"
if errorlevel 1 goto back_timeout
echo   Backend window started.
echo   Waiting for backend...

set /a WAIT=0
:wait_back
if exist "%LOG_DIR%\backend.exit" goto back_timeout
set /a WAIT+=1
curl --noproxy "*" -s -o nul --max-time 2 "http://127.0.0.1:!BACK_PORT!/api/jobs/sources" >nul 2>&1
if not errorlevel 1 goto back_ready
if !WAIT! geq 120 goto back_timeout
timeout /t 1 /nobreak >nul
goto wait_back

:back_timeout
echo.
echo [ERROR] Backend failed to start or did not become ready within 120 seconds.
echo         Log: "%LOG_DIR%\backend-startup.log"
if exist "%LOG_DIR%\backend-startup.log" powershell.exe -NoProfile -Command "Get-Content -LiteralPath (Join-Path $env:LOG_DIR 'backend-startup.log') -Tail 35"
pause
exit /b 1

:back_ready
echo   Backend ready.

REM ---------------------------------------------------------------- start frontend
set "VITE_PROXY_TARGET=http://127.0.0.1:!BACK_PORT!"
set "FRONT_PORT=!FRONT_PORT!"
if exist "%LOG_DIR%\frontend.exit" del /q "%LOG_DIR%\frontend.exit"
start "ShiJie Frontend" /D "%ROOT%frontend" "%ComSpec%" /d /k call "%ROOT%tools\run-frontend.cmd"
if errorlevel 1 goto front_timeout
echo   Frontend window started.
echo   Waiting for frontend...

set /a WAIT=0
:wait_front
if exist "%LOG_DIR%\frontend.exit" goto front_timeout
set /a WAIT+=1
curl --noproxy "*" -s -o nul --max-time 2 "http://127.0.0.1:!FRONT_PORT!/" >nul 2>&1
if not errorlevel 1 goto front_ready
if !WAIT! geq 120 goto front_timeout
timeout /t 1 /nobreak >nul
goto wait_front

:front_timeout
echo.
echo [ERROR] Frontend did not become ready within 120 seconds.
echo         Log: "%LOG_DIR%\frontend-startup.log"
if exist "%LOG_DIR%\frontend-startup.log" powershell.exe -NoProfile -Command "Get-Content -LiteralPath (Join-Path $env:LOG_DIR 'frontend-startup.log') -Tail 35"
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
if /i not "%~1"=="--no-browser" start "" "http://127.0.0.1:!FRONT_PORT!/login"
timeout /t 8 /nobreak >nul
exit /b 0
