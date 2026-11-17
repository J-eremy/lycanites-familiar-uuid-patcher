@echo off
setlocal
title Lycanites Mobs - Familiar UUID Patcher
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Patch-Jar.ps1"
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
    echo Something went wrong. Read the message above.
) else (
    echo You can close this window now.
)
echo.
pause >nul
exit /b %RC%
