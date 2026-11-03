@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0..\backend"
if not exist "%~dp0..\logs" mkdir "%~dp0..\logs"
echo Backend output is saved to logs\backend-startup.log
echo Keep this window open while using the application.
call mvnw.cmd -B -Dstyle.color=never -Dmaven.test.skip=true spring-boot:run > "%~dp0..\logs\backend-startup.log" 2>&1
set "RUN_EXIT=%ERRORLEVEL%"
>"%~dp0..\logs\backend.exit" echo %RUN_EXIT%
if not "%RUN_EXIT%"=="0" type "%~dp0..\logs\backend-startup.log"
exit /b %RUN_EXIT%
