# P3a 深度通道验收（AC-P3a-001~008）：可重复执行，只读 deploy\.env 取口令，不打印口令。
#
# 分工：verify-questions.ps1 回答"快速问答答得对不对"，本脚本回答"深度通道的机制有没有到位"。
# 两条沿用 §14 的判据：引用按"至少引到一份期望文档"（多引不判错）；每题一次实验，看机制不看波动。
# 深度通道还有两条快速模式没有的判据，这也是本脚本存在的理由：
#   1) 表行必须真的进证据：P27 要求 doc 8 的 chunk_index 11~14 出现在 evidences 里 ——
#      这条正是 §14 根因 1（第 7 名之后的片段进不了提示词）的回归位；
#   2) 长答案不许整段拒答：要么分段答出来，要么明确说哪一段缺证据。
#
# 深度分析慢（每题 5~20 秒、多轮模型调用），默认只跑 §15.3 圈定的四道第一批题 + 四项机制检查；
# 全量跑用 -All，只跑指定题用 -Ids P23,P27。
param(
    [string]$Set = 'p3-30.json',
    [string[]]$Ids = @('P23', 'P26', 'P27', 'P30'),
    [switch]$All,
    [switch]$SkipSlow
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

$setPath = Join-Path $PSScriptRoot ("questions\{0}" -f $Set)
if (-not (Test-Path -LiteralPath $setPath)) { throw "找不到题集：$setPath" }
$questions = (Get-Content -LiteralPath $setPath -Raw -Encoding UTF8 | ConvertFrom-Json).questions

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri = "http://127.0.0.1:9090/api$path"; Method = $method; Headers = $headers
        SkipHttpErrorCheck = $true; TimeoutSec = 300
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 6); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

# 模型产出的空格由排版决定，不由事实决定：比对前去掉空白，否则"8000h"与"8000 h"会被当成两回事。
function Squash($text) {
    if (-not $text) { return '' }
    return ($text -replace '\s', '')
}

function Check($name, $ok, $detail) {
    $flag = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("{0} {1} —— {2}" -f $flag, $name, $detail)
    $script:checks += [pscustomobject]@{ Name = $name; Ok = [bool]$ok }
}

$checks = @()

$tokens = @{
    admin = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
    demo  = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
}
if (-not $tokens.admin -or -not $tokens.demo) { throw '登录失败，无法跑深度验收' }

function Deep($token, $domain, $question, $runId, $extra) {
    $body = @{ domainCode = $domain; question = $question }
    if ($runId) { $body.runId = $runId }
    if ($extra) { foreach ($key in $extra.Keys) { $body[$key] = $extra[$key] } }
    return Call 'POST' '/deep/answer' $token $body
}

function AnswerText($answer) {
    $parts = @($answer.answer)
    if ($answer.sections) { $parts += @($answer.sections | ForEach-Object { $_.text }) }
    return ($parts -join ' ')
}

function ChunkIndexes($answer, $documentId) {
    $list = @()
    foreach ($evidence in @($answer.evidences)) {
        if ($evidence.documentId -ne $documentId) { continue }
        foreach ($fragment in @($evidence.fragments)) { $list += $fragment.chunkIndex }
    }
    return @($list)
}

function PlanDetail($answer) {
    return "子问题=$(@($answer.plan).Count) 证据组=$(@($answer.evidences).Count) 段=$(@($answer.sections).Count)/答$(($answer.diagnostics).answeredSections) 工具=$($answer.diagnostics.toolCalls) 轮=$($answer.diagnostics.rounds) 停止=$($answer.stopReason) 耗时=$($answer.diagnostics.elapsedMs)ms"
}

# ---------- AC-P3a-001~004：§15.3 的第一批题（默认四道，-All 跑全题集） ----------
$picked = if ($All) { $questions } else { @($questions | Where-Object { $Ids -contains $_.id }) }
if ($picked.Count -eq 0) { throw "题集 $Set 里没有匹配的题：$($Ids -join ', ')" }

Write-Host "题集：$Set；本轮 $($picked.Count) 题`n"

foreach ($item in $picked) {
    $token = $tokens[$item.asker]
    if (-not $token) { Check "$($item.id) 登录" $false "没有 $($item.asker) 的令牌"; continue }
    $r = Deep $token $item.domain $item.question $null $null
    $tag = "AC-P3a $($item.id)"
    if ($r.Status -ne 200) {
        Check "$tag 接口可用" $false "HTTP $($r.Status)：$($r.Raw)"
        continue
    }
    $ans = $r.Json
    $text = Squash (AnswerText $ans)
    # 证据不足时整段拒答是合法的（库里真没有就该拒），所以"拒答"本身不判失败；
    # 但期望作答的题一旦整段拒答，就是本切片要修的那件事 —— 单独点出来。
    $refused = ($ans.refused -eq $true)
    Check "$tag 不是整段拒答" (-not $refused -or $item.expect.outcome -ne 'answered') "$(PlanDetail $ans) 拒答=$refused 规则=$($ans.refusalRule)"
    $missing = @($item.expect.mention | Where-Object { $_ -and -not $text.Contains((Squash $_)) })
    Check "$tag 关键事实覆盖" ($missing.Count -eq 0) `
        $(if ($missing.Count -eq 0) { "要点 $($item.expect.mention -join '/') 全部出现" } else { "缺：$($missing -join '、')" })

    if ($item.id -eq 'P27') {
        # §14 根因 1 的回归位：四档周期表行在 doc 8 的 chunk_index 11~14，必须真进证据。
        $got = ChunkIndexes $ans 8
        $hit = @(11, 12, 13, 14 | Where-Object { $got -contains $_ })
        Check 'AC-P3a-001 表行进了证据（doc 8 的 chunk 11~14）' ($hit.Count -eq 4) `
            "命中 $(($hit -join ','))；doc 8 现有 chunk=$( (($got | Sort-Object) -join ',') )"
    }
    if ($item.id -eq 'P26') {
        $reads = @($ans.steps | Where-Object { $_.action -eq 'read_document' -and $_.documentId -eq 8 })
        Check 'AC-P3a-002 走 read_document 补齐上下文' ($reads.Count -ge 1) `
            "read_document 次数=$(($reads.Count))；新增片段=$(($reads | ForEach-Object { $_.newFragments }) -join ',')"
    }
    if ($item.id -eq 'P30') {
        Check 'AC-P3a-004 长答案不因截断整段拒答' (($ans.stopReason -ne 'OUTPUT_TRUNCATED') -and (@($ans.sections).Count -ge 1)) `
            "停止=$($ans.stopReason) 段=$(@($ans.sections).Count) 答=$(($ans.diagnostics).answeredSections)"
    }
}

# ---------- AC-P3a-005：预算封顶 ----------
$q27 = ($questions | Where-Object { $_.id -eq 'P27' } | Select-Object -First 1).question
$limited = Deep $tokens.admin 'repair' $q27 $null @{ maxToolCalls = 1 }
Check 'AC-P3a-005 工具调用预算封顶' `
    (($limited.Status -eq 200) -and ($limited.Json.diagnostics.toolCalls -le 1) -and ($limited.Json.diagnostics.budgetStop)) `
    "HTTP=$($limited.Status) 工具=$($limited.Json.diagnostics.toolCalls) 触顶说明=$($limited.Json.diagnostics.budgetStop)"

# ---------- AC-P3a-006：运行中取消 ----------
if ($SkipSlow) {
    Write-Host '[跳过] AC-P3a-006 取消演练（-SkipSlow）' -ForegroundColor DarkGray
} else {
    $runId = 'ac006-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $body = (@{ domainCode = 'repair'; question = $q27; runId = $runId } | ConvertTo-Json -Compress)
    $job = Start-Job -ScriptBlock {
        param($b, $t)
        Invoke-RestMethod 'http://127.0.0.1:9090/api/deep/answer' -Method Post -ContentType 'application/json' `
            -Headers @{ Authorization = "Bearer $t" } -Body $b -TimeoutSec 300
    } -ArgumentList $body, $tokens.admin
    Start-Sleep -Milliseconds 1200
    $cancel = Call 'POST' '/deep/cancel' $tokens.admin @{ runId = $runId }
    $partial = $null
    try { $partial = Receive-Job $job -Wait -AutoRemoveJob } catch { }
    Check 'AC-P3a-006 运行中可取消' `
        (($cancel.Status -eq 200) -and ($cancel.Json.cancelled -eq $true) -and ($partial.stopReason -eq 'CANCELLED')) `
        "取消=$($cancel.Json.cancelled)($($cancel.Json.note)) 停止=$($partial.stopReason) 已出段数=$(@($partial.sections).Count)"
    Check 'AC-P3a-006 取消不抛 500 且返回部分结果' `
        (($null -ne $partial) -and ($partial.mode -eq 'deep')) `
        "mode=$($partial.mode) 答案长度=$($partial.answer.Length)"
}

# ---------- AC-P3a-007：单跳题走深度不能变差 ----------
$p1Path = Join-Path $PSScriptRoot 'questions\p1-30.json'
if (Test-Path -LiteralPath $p1Path) {
    $single = (Get-Content -LiteralPath $p1Path -Raw -Encoding UTF8 | ConvertFrom-Json).questions |
        Where-Object { $_.expect.outcome -eq 'answered' } | Select-Object -First 1
    $deepOne = Deep $tokens.admin $single.domain $single.question $null $null
    $docNos = @($deepOne.Json.citations | ForEach-Object { $_.docNo })
    $allowed = @($single.expect.allowedDocNos)
    Check 'AC-P3a-007 单跳题在深度模式下仍答对' `
        (($deepOne.Status -eq 200) -and -not $deepOne.Json.refused -and (@($allowed | Where-Object { $docNos -contains $_ }).Count -ge 1)) `
        "引用=$($docNos -join ',') 期望=$($allowed -join ',') 停止=$($deepOne.Json.stopReason)"
} else {
    Check 'AC-P3a-007 单跳题在深度模式下仍答对' $false '找不到 p1-30.json'
}

# ---------- AC-P3a-008：无权限文档（含 read_document 路径）不透出 ----------
# demo 看不见 doc 1（policy / QM-ZD-2026-021 /《2026 年度薪酬结构调整方案》/ 秘密）。
# 问题本身刻意不含编号与标题，否则"问题里就有"会让断言恒真。响应全文（含 steps、证据）都不许出现。
$secret = Deep $tokens.demo 'policy' '总监这一档的薪酬带宽上下限和调整幅度分别是多少' $null $null
$leak = @('QM-ZD-2026-021', '薪酬结构调整方案', '32000', '48000') | Where-Object { $secret.Raw -match [regex]::Escape($_) }
Check 'AC-P3a-008 demo 走深度不泄露无权限文档' ($leak.Count -eq 0) `
    $(if ($leak.Count -eq 0) { "响应全文（$($secret.Raw.Length) 字符）不含编号/标题/正文值；拒绝=$(($secret.Json).refused)" } else { "漏出：$($leak -join '、')" })

'' 
'==================== 汇总 ===================='
$failed = @($checks | Where-Object { -not $_.Ok })
Write-Host ("检查项 {0} 项，通过 {1}，失败 {2}" -f $checks.Count, ($checks.Count - $failed.Count), $failed.Count)
$failed | ForEach-Object { '  [FAIL] ' + $_.Name }
if ($failed.Count -gt 0) { exit 1 }
