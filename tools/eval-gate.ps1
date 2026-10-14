# 评测回归门（P3）：跑一次题集 → 与同题集的基线比 → 关键指标退步就非零退出。
#
# 为什么要有退出码：tools\run-acceptance.ps1 只统计 [OK]/[FAIL] 文本计数，永远以 0 退出，
# 所以"跑完看一眼"是唯一的使用方式。有了退出码，它才能被任何东西（人手敲的命令、
# 将来的流水线）当成一道门：退出 0 = 可以合，非 0 = 先看 Δ。
#
# 三条红线（数量级是拍的，痛过一次再调）：
#   1. 泄露数 > 0            —— 权限回归，任何情况都不接受，不看 Δ；
#   2. 通过率下降 > 5 个百分点 —— 一整批题开始答错，不是噪声；
#   3. hit@k 下降 > 3 个百分点 —— 检索召回变差（换分词、调切分最容易踩这条）。
#
# 用法：
#   pwsh tools\eval-gate.ps1                          # 默认 p1-30.json + retrieval（秒级、不花钱）
#   pwsh tools\eval-gate.ps1 -Mode full               # 全链路（几分钟、真花模型钱）
#   pwsh tools\eval-gate.ps1 -Set p3-30.json -SkipRun # 不跑新的，只比最近两次
#   pwsh tools\eval-gate.ps1 -Baseline 42             # 指定基线 run id
param(
    [string]$Set = 'p1-30.json',
    [ValidateSet('retrieval', 'full')]
    [string]$Mode = 'retrieval',
    [int]$Baseline = 0,
    [switch]$SkipRun,
    [double]$MaxPassDrop = 0.05,
    [double]$MaxHitDrop = 0.03
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$envPath = @((Join-Path $PSScriptRoot '..\deploy\.env'), (Join-Path $PSScriptRoot '.env')) |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $envPath) { throw '找不到 deploy\.env，无法取口令' }
$envFile = Get-Content -LiteralPath $envPath -Encoding UTF8
function EnvValue($key) {
    ($envFile | Where-Object { $_ -match "^\s*$key=" } | Select-Object -First 1) -replace "^\s*$key=", ''
}

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri = "http://127.0.0.1:9090/api$path"; Method = $method; Headers = $headers
        SkipHttpErrorCheck = $true; TimeoutSec = 900
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 6); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

$token = (Call 'POST' '/auth/login' $null @{
    username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD')
}).Json.token
if (-not $token) { throw '登录失败，无法跑评测' }

if (-not $SkipRun) {
    Write-Host "运行评测：$Set（$Mode）…"
    if ($Mode -eq 'full') { Write-Host '  全链路会真调生成模型，30 题约几分钟。' -ForegroundColor Yellow }
    $run = Call 'POST' '/eval/runs' $token @{ set = $Set; mode = $Mode }
    if ($run.Status -ne 200) {
        Write-Host "[FAIL] 评测没跑起来（HTTP $($run.Status)）：$($run.Raw)" -ForegroundColor Red
        exit 1
    }
    Write-Host ("  本轮 run=#{0} 通过 {1}/{2} hit@k {3:P1}" -f $run.Json.run.id, $run.Json.run.passed,
        $run.Json.run.total, $run.Json.run.hitAtK)
}

$history = (Call 'GET' "/eval/runs?limit=50&set=$Set" $token $null).Json.runs
if (-not $history -or $history.Count -lt 2) {
    Write-Host '只有一次记录，没有可比基线——本次只报告，不算退步。'
    if ($history -and $history[0].leakCount -gt 0) {
        Write-Host "[FAIL] 泄露 $($history[0].leakCount) 条（权限回归，无视基线）" -ForegroundColor Red
        exit 1
    }
    exit 0
}

$current = $history[0]
$base = if ($Baseline -gt 0) {
    $history | Where-Object { $_.id -eq $Baseline } | Select-Object -First 1
} else {
    # 默认拿"同题集的上一条"：跨题集比通过率等于拿两次不同的考试比分数
    $history | Where-Object { $_.id -ne $current.id -and $_.questionSet -eq $current.questionSet } |
        Select-Object -First 1
}
if (-not $base) {
    Write-Host "找不到基线（-Baseline $Baseline）；本次只报告，不算退步。"
    exit 0
}

function Pct($value) {
    if ($null -eq $value) { return '—' }
    return ('{0:P1}' -f [double]$value)
}

function Delta($now, $before) {
    if ($null -eq $now -or $null -eq $before) { return $null }
    return [double]$now - [double]$before
}

function DeltaText($value) {
    if ($null -eq $value) { return '—' }
    $points = $value * 100
    if ($points -gt 0.0001) { return ('▲{0:N1}' -f $points) }
    if ($points -lt -0.0001) { return ('▼{0:N1}' -f [math]::Abs($points)) }
    return '='
}

$passNow = if ($current.total -gt 0) { $current.passed / $current.total } else { 0 }
$passBefore = if ($base.total -gt 0) { $base.passed / $base.total } else { 0 }
$passDelta = $passNow - $passBefore
$hitDelta = Delta $current.hitAtK $base.hitAtK
$mrrDelta = Delta $current.mrr $base.mrr

Write-Host ''
Write-Host ("---------- 回归门：run #{0} vs 基线 #{1}（{2}） ----------" -f $current.id, $base.id, $current.questionSet)
Write-Host ("  通过率    {0:N1}% → {1:N1}%（{2}）" -f ($passBefore * 100), ($passNow * 100), (DeltaText $passDelta))
Write-Host ("  hit@k     {0} → {1}（{2}）" -f (Pct $base.hitAtK), (Pct $current.hitAtK), (DeltaText $hitDelta))
Write-Host ("  MRR       {0:N3} → {1:N3}" -f [double]$base.mrr, [double]$current.mrr)
Write-Host ("  泄露      {0} → {1}" -f $base.leakCount, $current.leakCount)

$failed = @()
if ($current.leakCount -gt 0) {
    $failed += "泄露 $($current.leakCount) 条（权限回归，红线）"
}
if ($passDelta -lt -$MaxPassDrop) {
    $failed += ("通过率下降 {0:N1} 个百分点（红线 {1:N1}）" -f ([math]::Abs($passDelta) * 100), ($MaxPassDrop * 100))
}
if ($null -ne $hitDelta -and $hitDelta -lt -$MaxHitDrop) {
    $failed += ("hit@k 下降 {0:N1} 个百分点（红线 {1:N1}）" -f ([math]::Abs($hitDelta) * 100), ($MaxHitDrop * 100))
}

if ($failed.Count -gt 0) {
    Write-Host ''
    foreach ($reason in $failed) { Write-Host "[FAIL] $reason" -ForegroundColor Red }
    Write-Host "明细：GET /api/eval/runs/$($current.id)（或管理控制台 → 评测 → 明细）"
    exit 1
}

Write-Host ''
Write-Host '[OK] 没有越过红线' -ForegroundColor Green
exit 0
