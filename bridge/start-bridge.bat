@echo off
title Antigravity Remote Bridge
powershell.exe -NoLogo -ExecutionPolicy Bypass -File "%~dp0start-bridge.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Bridge exited with error code %ERRORLEVEL%.
    pause
)
