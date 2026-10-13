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

# pid 文件可能过期，pid 也可能被系统复用给别的进程。这里只认"还活着、且确实是 JVM"的那一个，
# 认不出来就当它不存在——误杀一个不认识的 pid，代价比留一个僵尸后端大得多。
function Get-RecordedBackend {
    if (-not (Test-Path -LiteralPath $pidFile)) { return $null }
    $raw = Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue
    if (-not $raw) { return $null }
    $recordedPid = 0
    if (-not [int]::TryParse($raw.Trim(), [ref]$recordedPid)) { return $null }
    $proc = Get-Process -Id $recordedPid -ErrorAction SilentlyContinue
    if (-not $proc) { return $null }
    if ($proc.ProcessName -ne 'java') { return $null }
    return $proc
}

$old = Get-RecordedBackend
if ($old) {
    Stop-Process -Id $old.Id -Force
    Write-Host "已停止旧后端进程 pid=$($old.Id)" -ForegroundColor DarkGray
    Start-Sleep -Seconds 1
}
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue

$launcher = Start-Process -FilePath $pwsh `
    -ArgumentList '-NoLogo', '-NoProfile', '-File', (Join-Path $PSScriptRoot 'start-backend.ps1') `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logs 'app.log') `
    -RedirectStandardError (Join-Path $logs 'app.err.log') `
    -PassThru

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

# 用真正的墙钟截止时间，而不是"轮询次数 × 间隔"：
# 慢失败（连接超时）时每次轮询要耗好几秒，按次数算能把 60 秒拖成好几分钟，看着就是卡死。
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$nextNotice = (Get-Date).AddSeconds(5)
$check = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500

    # 启动器已经退出 = JVM 根本没起来（瘦 jar、端口被占、配置错），再等也没有意义
    if ($launcher.HasExited) {
        Write-Host "后端进程已退出（exit=$($launcher.ExitCode)），错误输出：" -ForegroundColor Red
        Get-Content (Join-Path $logs 'app.err.log') -Tail 30 -ErrorAction SilentlyContinue
        Get-Content (Join-Path $logs 'app.log') -Tail 20 -ErrorAction SilentlyContinue
        exit 1
    }

    try {
        $token = (Invoke-RestMethod 'http://127.0.0.1:9090/api/auth/login' -Method Post `
            -ContentType 'application/json' -TimeoutSec 5 `
            -Body (@{ username = 'admin'; password = $adminPassword } | ConvertTo-Json)).token
        $check = Invoke-RestMethod 'http://127.0.0.1:9090/api/domains/selfcheck' -TimeoutSec 10 `
            -Headers @{ Authorization = "Bearer $token" }
        break
    } catch { }

    # 别静默等待：JVM 冷启动要十几秒，没有进度输出时最像"卡住"
    if ((Get-Date) -ge $nextNotice) {
        $left = [int]($deadline - (Get-Date)).TotalSeconds
        Write-Host "等待后端就绪…（剩余 ${left}s，日志 backend\logs\app.log）" -ForegroundColor DarkGray
        $nextNotice = (Get-Date).AddSeconds(5)
    }
}

if (-not $check) {
    Write-Host "后端未在 $TimeoutSeconds 秒内就绪，错误输出：" -ForegroundColor Red
    Get-Content (Join-Path $logs 'app.err.log') -Tail 30 -ErrorAction SilentlyContinue
    Get-Content (Join-Path $logs 'app.log') -Tail 20 -ErrorAction SilentlyContinue
    exit 1
}

# pid 由 start-backend.ps1 在拉起 JVM 时写入，这里只做校验与兜底重读
$backend = $null
for ($i = 0; $i -lt 25 -and -not $backend; $i++) {
    $backend = Get-RecordedBackend
    if (-not $backend) { Start-Sleep -Milliseconds 200 }
}

Write-Host "后端已就绪（pid=$($backend.Id)），日志：backend\logs\app.log" -ForegroundColor Cyan
Write-Host '--- GET /api/domains/selfcheck ---'
$check | ConvertTo-Json -Depth 6

# 进程起来 ≠ 业务可用：检索链路 / 向量化配置这类自检项失败时 ok=false。
# 这时不能返回 0，否则一键启动会假装成功，问题被拖到第一次提问才暴露。
if ($check.PSObject.Properties.Name -contains 'ok' -and -not $check.ok) {
    Write-Warning '后端进程已启动，但自检未全部通过（详见上面 JSON 与日志）。'
    exit 2
}

exit 0
