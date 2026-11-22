@echo off
rem Antigravity Remote - double-click to start the bridge.
title Antigravity Remote Bridge
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-bridge.ps1" %*
if errorlevel 1 (
    echo.
    echo The bridge stopped with an error.
    pause
)
