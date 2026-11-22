@echo off
title Antigravity Remote Bridge (Remote Tunnel)
powershell.exe -NoLogo -ExecutionPolicy Bypass -File "%~dp0start-bridge.ps1" -Tunnel %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Bridge stopped with exit code %ERRORLEVEL%.
    pause
)
