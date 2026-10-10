@echo off
rem One-click launcher: logic lives in deploy\start-all.ps1. Args pass through, e.g. 启动.bat -Rebuild -SkipFrontend
setlocal
cd /d "%~dp0"
where pwsh >nul 2>nul
if %errorlevel%==0 (set "PS=pwsh") else (set "PS=powershell")
"%PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\start-all.ps1" %*
if errorlevel 1 pause
endlocal
