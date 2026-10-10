# 串行跑完整验收集（P1 + P2），结果落 target\acceptance\，控制台只打每项计数与失败行。
#
# 为什么必须串行：脚本会改状态（改密、注销会话、临时改 ACL、临时改密钥、强制重解析），
# 并行执行时会互相注销令牌或互相覆盖状态，表现为一堆 401 假失败。
#
# 前置条件（缺一个就会大面积假失败）：
#   1. 后端 9090、前端 5173 都在跑；
#   2. 终端不受沙箱限制（脚本要访问 Docker 管道，后端 JVM 要出网调模型）。
# 用法：pwsh tools\run-acceptance.ps1
param(
    [switch]$SkipSlow          # 跳过 30 题跑分与界面实拍，只跑快的
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'target\acceptance'
if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$steps = @(
    @{ Name = 'P1-权限';   Kind = 'ps1';  File = 'verify-auth.ps1';         Slow = $false },
    @{ Name = 'P1-检索';   Kind = 'ps1';  File = 'verify-retrieval.ps1';    Slow = $false },
    @{ Name = 'P1-问答';   Kind = 'ps1';  File = 'verify-generation.ps1';   Slow = $false },
    @{ Name = 'P2-解析';   Kind = 'ps1';  File = 'verify-parse.ps1';        Slow = $false },
    @{ Name = 'P2-混合';   Kind = 'ps1';  File = 'verify-hybrid.ps1';       Slow = $false },
    @{ Name = 'P0-重排';   Kind = 'ps1';  File = 'verify-rerank.ps1';       Slow = $false },
    @{ Name = 'P1-管理';   Kind = 'ps1';  File = 'verify-management.ps1';   Slow = $false },
    @{ Name = 'P2-文档查看'; Kind = 'ps1'; File = 'verify-document-view.ps1'; Slow = $false },
    @{ Name = 'P1-观测';   Kind = 'ps1';  File = 'verify-observe.ps1';      Slow = $false },
    @{ Name = 'P1-安全';   Kind = 'ps1';  File = 'verify-security.ps1';     Slow = $false },
    @{ Name = 'P1-无权限域'; Kind = 'mjs'; File = 'verify-noperm-domain.mjs'; Slow = $false },
    @{ Name = 'P1-30题';   Kind = 'ps1';  File = 'verify-questions.ps1';    Slow = $true },
    @{ Name = 'P3a-深度';  Kind = 'ps1';  File = 'verify-deep.ps1';        Slow = $true },
    @{ Name = 'P1-问答页'; Kind = 'mjs';  File = 'verify-ask-page.mjs';     Slow = $true },
    @{ Name = 'P2-文档查看页'; Kind = 'mjs'; File = 'verify-document-view-ui.mjs'; Slow = $true }
)

$summary = @()
foreach ($s in $steps) {
    if ($SkipSlow -and $s.Slow) { continue }
    $log = Join-Path $outDir ("{0}-{1}.log" -f $s.Name, ($s.File -replace '\.[a-z]+$', ''))
    Write-Host ("===== {0} ({1}) =====" -f $s.Name, $s.File)
    if ($s.Kind -eq 'ps1') {
        $out = & pwsh -NoLogo -NoProfile -File (Join-Path $PSScriptRoot $s.File) 2>&1
    } else {
        $out = & node (Join-Path $PSScriptRoot $s.File) 2>&1
    }
    $out | Set-Content -LiteralPath $log -Encoding UTF8
    $ok = ($out | Select-String -Pattern '\[OK\]').Count
    $fail = ($out | Select-String -Pattern '\[FAIL\]').Count
    $summary += [pscustomobject]@{ 项目 = $s.Name; 脚本 = $s.File; OK = $ok; FAIL = $fail }
    if ($fail -gt 0) { ($out | Select-String -Pattern '\[FAIL\]').Line | ForEach-Object { '   ' + $_ } }
}

''
'==================== 汇总 ===================='
$summary | Format-Table -AutoSize | Out-String | Write-Host
$totalFail = ($summary | Measure-Object -Property FAIL -Sum).Sum
Write-Host ("失败合计：{0}；日志在 {1}" -f $totalFail, $outDir)
