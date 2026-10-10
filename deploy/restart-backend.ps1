# 重启后端：停掉旧进程 → 按 start-backend.ps1 启动（日志落 backend\logs）→ 等就绪并打印启动自检。
# 后端 JVM 要访问公网模型接口，必须在不受沙箱限制的终端里执行，否则会报 Permission denied。
param(
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'backend\logs'
$pidFile = Join-Path $logs 'app.pid'
$pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
if (-not $pwsh) { $pwsh = 'powershell' }

if (-not (Test-Path -LiteralPath $logs)) { New-Item -ItemType Directory -Path $logs | Out-Null }

if (Test-Path -LiteralPath $pidFile) {
    $old = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($old -and (Get-Process -Id ([int]$old) -ErrorAction SilentlyContinue)) {
        Stop-Process -Id ([int]$old) -Force
        Start-Sleep -Seconds 1
    }
}

Start-Process -FilePath $pwsh `
    -ArgumentList '-NoLogo', '-NoProfile', '-File', (Join-Path $PSScriptRoot 'start-backend.ps1') `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logs 'app.log') `
    -RedirectStandardError (Join-Path $logs 'app.err.log')

# 探活必须带令牌：T06 起 /api/** 默认拒绝，selfcheck 也不再是匿名接口。
# 用「登录 + selfcheck」作为一个整体探针，一次判出"进程活着"和"业务可用"两件事。
$envFile = Join-Path $PSScriptRoot '.env'
$adminPassword = $null
if (Test-Path -LiteralPath $envFile) {
    $line = Get-Content -LiteralPath $envFile -Encoding UTF8 |
        Where-Object { $_ -match '^\s*ASSISTANT_ADMIN_PASSWORD=' } | Select-Object -First 1
    if ($line) { $adminPassword = ($line -split '=', 2)[1].Trim() }
}
if (-not $adminPassword) {
    Write-Host '缺少 ASSISTANT_ADMIN_PASSWORD，无法探活；请检查 deploy\.env' -ForegroundColor Red
    exit 1
}

$check = $null
for ($i = 0; $i -lt $TimeoutSeconds * 2; $i++) {
    Start-Sleep -Milliseconds 500
    try {
        $token = (Invoke-RestMethod 'http://127.0.0.1:9090/api/auth/login' -Method Post `
            -ContentType 'application/json' -TimeoutSec 5 `
            -Body (@{ username = 'admin'; password = $adminPassword } | ConvertTo-Json)).token
        $check = Invoke-RestMethod 'http://127.0.0.1:9090/api/domains/selfcheck' -TimeoutSec 10 `
            -Headers @{ Authorization = "Bearer $token" }
        break
    } catch { }
}
if (-not $check) {
    Write-Host "后端未在 $TimeoutSeconds 秒内就绪，错误输出：" -ForegroundColor Red
    Get-Content (Join-Path $logs 'app.err.log') -Tail 30 -ErrorAction SilentlyContinue
    Get-Content (Join-Path $logs 'app.log') -Tail 20 -ErrorAction SilentlyContinue
    exit 1
}

$java = Get-Process java -ErrorAction SilentlyContinue | Sort-Object StartTime -Descending | Select-Object -First 1
if ($java) { Set-Content -LiteralPath $pidFile -Value $java.Id -Encoding ascii }

Write-Host "后端已就绪（pid=$($java.Id)），日志：backend\logs\app.log" -ForegroundColor Cyan
Write-Host '--- GET /api/domains/selfcheck ---'
$check | ConvertTo-Json -Depth 6
