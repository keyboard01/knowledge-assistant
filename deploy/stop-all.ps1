# 一键停止：后端（按 pid 文件）+ 前端（按端口 5173/5174 找监听进程）。
# 数据库容器（assistant-pg）**保持运行**——里面是数据，要停用 docker stop <name>。
# 用法：双击根目录「停止.bat」，或 pwsh deploy\stop-all.ps1
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

# ---------- 后端 ----------
$pidFile = Join-Path $root 'backend\logs\app.pid'
if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host '未找到后端 pid 文件（可能未启动）' -ForegroundColor Yellow
} else {
    $raw = Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue
    $backendPid = 0
    $backendProc = $null
    if ($raw -and [int]::TryParse($raw.Trim(), [ref]$backendPid)) {
        $backendProc = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
    }
    if (-not $backendProc) {
        Write-Host '后端 pid 文件存在，但对应进程已不在' -ForegroundColor Yellow
    } elseif ($backendProc.ProcessName -ne 'java') {
        # pid 会被系统复用：不确认这是 JVM 就动手，可能杀掉系统上完全无关的进程
        Write-Host "pid 文件里的 pid=$backendPid 现在是 $($backendProc.ProcessName)，不是后端 JVM，跳过" -ForegroundColor Yellow
    } else {
        Stop-Process -Id $backendPid -Force
        Write-Host "已停止后端进程 pid=$backendPid" -ForegroundColor Cyan
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

# ---------- 前端（按端口找监听进程） ----------
# Get-NetTCPConnection 在受限终端里可能整个返回空（实测沙箱下就是 0 条），
# 那样会被误判成"前端没在跑"而静默漏停，所以退回 netstat 解析。
function Get-ListeningPid([int]$port) {
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($connections) {
        return @($connections | ForEach-Object { $_.OwningProcess } | Where-Object { $_ -gt 0 } | Select-Object -Unique)
    }
    return @(
        netstat -ano |
            Select-String ":$port\s+[^\s]+\s+LISTENING\s+(\d+)" |
            ForEach-Object { [int]$_.Matches[0].Groups[1].Value } |
            Where-Object { $_ -gt 0 } |
            Select-Object -Unique
    )
}

$frontendStopped = $false
foreach ($port in 5173, 5174) {
    foreach ($frontendPid in (Get-ListeningPid $port)) {
        Stop-Process -Id $frontendPid -Force -ErrorAction SilentlyContinue
        Write-Host "已停止监听 $port 的进程 pid=$frontendPid" -ForegroundColor Cyan
        $frontendStopped = $true
    }
}
if ($frontendStopped) {
    Write-Host '前端那个 npm 窗口如果还开着，直接关掉即可。' -ForegroundColor DarkGray
} else {
    Write-Host '未发现监听 5173/5174 的前端进程' -ForegroundColor Yellow
}

Write-Host '数据库容器保持运行；要停用：docker stop assistant-pg' -ForegroundColor DarkGray
