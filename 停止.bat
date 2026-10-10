@echo off
rem One-click stopper: logic lives in deploy\stop-all.ps1.
setlocal
cd /d "%~dp0"
where pwsh >nul 2>nul
if %errorlevel%==0 (set "PS=pwsh") else (set "PS=powershell")
"%PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\stop-all.ps1" %*
if errorlevel 1 pause
endlocal
