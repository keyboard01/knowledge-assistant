@echo off
setlocal
for %%I in ("%~dp0.") do set "PROJECT_ROOT=%%~fI"
set "WT_ROOT=D:\WindowsTerminal"
set "WT_APP=%WT_ROOT%\app\terminal-1.24.11321.0"
set "WT_SETTINGS=%WT_APP%\settings\settings.json"
set "PWSH_EXE=D:\powerShell 7.6.1\PowerShell-7.6.1-win-x64\pwsh.exe"
set "TEMP=%WT_ROOT%\cache"
set "TMP=%WT_ROOT%\cache"

if not exist "%PWSH_EXE%" set "PWSH_EXE=pwsh.exe"
if not exist "%WT_SETTINGS%" exit /b 1

"%PWSH_EXE%" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $path=$env:WT_SETTINGS; $settings=Get-Content -Raw -LiteralPath $path | ConvertFrom-Json; if ($null -eq $settings.profiles.defaults) { $settings.profiles | Add-Member -NotePropertyName defaults -NotePropertyValue ([pscustomobject]@{}) }; $settings.profiles.defaults | Add-Member -NotePropertyName startingDirectory -NotePropertyValue $env:PROJECT_ROOT -Force; $defaultGuid=[string]$settings.defaultProfile; $defaultProfile=$settings.profiles.list | Where-Object { [string]$_.guid -eq $defaultGuid } | Select-Object -First 1; if ($null -ne $defaultProfile) { $defaultProfile | Add-Member -NotePropertyName startingDirectory -NotePropertyValue $env:PROJECT_ROOT -Force }; $customId='User.PasteImageToCodex'; $targetKeys=@('ctrl+c','ctrl+v','ctrl+shift+v'); $settings.actions=@($settings.actions | Where-Object { $_.id -ne $customId }); $settings.actions += [pscustomobject]@{ command=[pscustomobject]@{ action='sendInput'; input=[string][char]0x16 }; id=$customId }; $settings.keybindings=@($settings.keybindings | Where-Object { $bindingKeys=@($_.keys) | ForEach-Object { ([string]$_).ToLowerInvariant() }; -not ($bindingKeys | Where-Object { $targetKeys -contains $_ }) }); $settings.keybindings += [pscustomobject]@{ keys='ctrl+c'; id='Terminal.CopyToClipboard' }; $settings.keybindings += [pscustomobject]@{ keys='ctrl+v'; id='Terminal.PasteFromClipboard' }; $settings.keybindings += [pscustomobject]@{ keys='ctrl+shift+v'; id=$customId }; $json=$settings | ConvertTo-Json -Depth 100; [System.IO.File]::WriteAllText($path, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))"
if errorlevel 1 exit /b 1

start "Windows Terminal - Codex" /D "%PROJECT_ROOT%" "%WT_APP%\wt.exe" -d "%PROJECT_ROOT%"
endlocal
