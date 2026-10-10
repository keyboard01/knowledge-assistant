# 一键停止：后端（按 pid 文件）+ 前端（按端口 5173/5174 找监听进程）。
# 容器（assistant-pg / assistant-anythingllm）**保持运行**——里面是数据，要停用 docker stop <name>。
# 用法：双击根目录「停止.bat」，或 pwsh deploy\stop-all.ps1
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

# ---------- 后端 ----------
$pidFile = Join-Path $root 'backend\logs\app.pid'
if (Test-Path -LiteralPath $pidFile) {
    $raw = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    $backendPid = 0
    if ($raw -and [int]::TryParse($raw, [ref]$backendPid) -and (Get-Process -Id $backendPid -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $backendPid -Force
        Write-Host "已停止后端进程 pid=$backendPid" -ForegroundColor Cyan
    } else {
        Write-Host '后端 pid 文件存在，但对应进程已不在' -ForegroundColor Yellow
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
} else {
    Write-Host '未找到后端 pid 文件（可能未启动）' -ForegroundColor Yellow
}

# ---------- 前端（按端口找监听进程） ----------
foreach ($port in 5173, 5174) {
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($connection in $connections) {
        $frontendPid = $connection.OwningProcess
        if ($frontendPid) {
            Stop-Process -Id $frontendPid -Force -ErrorAction SilentlyContinue
            Write-Host "已停止监听 $port 的进程 pid=$frontendPid" -ForegroundColor Cyan
        }
    }
}

Write-Host '容器保持运行；要停用：docker stop assistant-anythingllm assistant-pg' -ForegroundColor DarkGray
