@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0..\frontend"
if not exist "%~dp0..\logs" mkdir "%~dp0..\logs"
if not defined FRONT_PORT set "FRONT_PORT=5173"
echo Frontend output is saved to logs\frontend-startup.log
echo Keep this window open while using the application.
call npm.cmd run dev -- --port %FRONT_PORT% --strictPort --host 127.0.0.1 > "%~dp0..\logs\frontend-startup.log" 2>&1
set "RUN_EXIT=%ERRORLEVEL%"
>"%~dp0..\logs\frontend.exit" echo %RUN_EXIT%
if not "%RUN_EXIT%"=="0" type "%~dp0..\logs\frontend-startup.log"
exit /b %RUN_EXIT%
