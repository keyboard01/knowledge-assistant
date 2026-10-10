# T11 验收题集跑分（30 题）：可重复执行，只读 deploy\.env 取口令，不打印口令。
#
# 三类题一起跑，因为它们要回答的是不同的问题：
#   应作答 20 题 —— "该答的答上来了吗"（引对文档、说对要点）
#   库里没有 5 题 —— "不该答的有没有硬答"（这条决定召回下限够不够严）
#   无权限 5 题 —— "看不见的东西有没有漏出来"（必须告诉用户是权限问题，且不带出被挡文档的专属内容）
#
# 有两条判据是刻意这么定的，别改成"看起来更严"的版本：
#   1) 引用：要求"至少引到一份期望文档"，**不禁止多引**。模型把同一域里另一份相关文档一并引上
#      （还标了"另外"），是正常行为、不是错引；真正要挡的是"引了错的、没引对的"，
#      这由"至少引到一份期望文档" + "关键数字必须出现"覆盖。
#   2) 权限题：拒答与放行都算通过，但**响应全文必须出现权限话术**（"无权访问"/"没有访问权限"）。
#      （T07/T09 已定：要么拒答理由说成权限，要么答案旁挂权限提示），只要用户被告知
#      "是权限问题、该找管理员"就达标；反过来，漏出被挡文档的专属内容一律失败。
#
# 末尾把"库里没有"的最高分与"应作答"的最低分摆在一起，给出阈值区间——
# ANSWER_MIN_SCORE 就在这两个数中间生存，这是 T11 唯一一件"不改代码就没意义"的产出。
#
# 注意：本脚本会真调用生成模型 30 次（约 1~3 分钟），并按结果写入运行记录。
param(
    # 题集文件名（相对 tools\questions\）。默认是 P1 的 30 题；跑 P3 的触发证据题集用 -Set p3-30.json。
    [string]$Set = 'p1-30.json'
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
        SkipHttpErrorCheck = $true; TimeoutSec = 180
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 6); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

# 中英文之间的空格由排版决定，不由事实决定：比对前先把空白全去掉，
# 否则"2026 年 7 月 1 日"与"2026年7月1日"会被当成两个不同的东西。
function Squash($text) {
    if (-not $text) { return '' }
    return ($text -replace '\s', '')
}

$tokens = @{
    admin = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
    demo  = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
}
if (-not $tokens.admin -or -not $tokens.demo) { throw '登录失败，无法跑题' }

# 题集自检：noLeak 的词若出现在问题里，那条断言恒为真（用户自己打的字不算泄露）——当场点出来。
foreach ($item in $questions) {
    foreach ($secret in @($item.expect.noLeak)) {
        if ($secret -and $item.question -match [regex]::Escape($secret)) {
            Write-Host "[题集自检] $($item.id)：noLeak 词 '$secret' 出现在问题里，这条断言永远为真，请换成只存在于被挡文档里的词" -ForegroundColor Yellow
        }
    }
}

Write-Host "题集：$($questions.Count) 题`n"
Write-Host ("{0,-5} {1,-7} {2,-7} {3,-8} {4,-6} {5,-8} {6}" -f 'ID', '知识域', '提问人', '期望', '实际', '最高分', '结论/失败原因')
Write-Host ('-' * 108)

$results = @()
foreach ($item in $questions) {
    $response = Call 'POST' '/generation/answer' $tokens.($item.asker) @{
        domainCode = $item.domain; question = $item.question; topN = 5
    }
    $json = $response.Json
    $squashed = Squash $response.Raw
    # 账本里最新几条就是刚刚这次：最高分只有账本记（接口响应里不带它）
    $trace = $null
    if ($response.Status -eq 200) {
        $latest = (Call 'GET' '/observe/traces?limit=3' $tokens.admin $null).Json.traces
        $mine = @($latest | Where-Object { $_.question -eq $item.question })
        if ($mine.Count -gt 0) { $trace = $mine[0] }
    }

    $fail = @()
    $notes = @()
    $actual = '异常'
    $refused = $false
    $docNos = @()
    $cited = 0
    if ($response.Status -ne 200) {
        $fail += "HTTP $($response.Status)"
    } else {
        $refused = [bool]$json.refused
        $actual = if ($refused) { '拒答' } else { '作答' }
        $cited = @($json.citations).Count
        $docNos = @($json.citations | ForEach-Object { $_.docNo } | Select-Object -Unique)

        foreach ($docNo in $docNos) {
            if (@($item.expect.forbiddenDocNos) -contains $docNo) { $fail += "引用了不该引用的文档 $docNo" }
        }
        if ($refused -and $cited -ne 0) { $fail += '拒答却带了引用' }

        if ($item.expect.permission) {
            # 权限题：两条路都行，但"用户被告知是权限问题"不能少
            # 权限话术有两种：全部被挡下说"没有访问权限"（在 hint 里），部分被挡下说"无权访问"
            # （在 accessNotice 里）。两种都是设计内的，这里只钉"理由是权限"，不钉具体句式。
            if ($response.Raw -notmatch '权访问|访问权限') {
                $fail += '没告诉用户这是权限问题（响应里没有任何权限话术）'
            }
            foreach ($secret in @($item.expect.noLeak)) {
                if ($squashed -match [regex]::Escape((Squash $secret))) {
                    $fail += "漏出了被挡文档的专属内容 '${secret}'"
                }
            }
            $notes += if ($refused) { '按权限拒答' } else { '放行 + 权限提示' }
        } elseif ($item.expect.outcome -eq 'answered') {
            if ($refused) {
                # 拒答原因码决定下一步该改什么：INSUFFICIENT_EVIDENCE 要改检索，
                # OUTPUT_TRUNCATED / NO_CITATION 要改提示词，权限类要找管理员。不写清楚等于白跑。
                $ruleCode = $json.diagnostics.refusalRule
                $reasonText = Squash $json.refusalReason
                if ($reasonText.Length -gt 70) { $reasonText = $reasonText.Substring(0, 70) + '…' }
                $fail += "本该作答却拒答（$ruleCode）：$reasonText"
            } else {
                if ($cited -lt 1) { $fail += '没有引用' }
                $expectedDocs = @($item.expect.allowedDocNos)
                if ($expectedDocs.Count -gt 0) {
                    if (@($docNos | Where-Object { $expectedDocs -contains $_ }).Count -eq 0) {
                        $fail += "没有引用期望的文档（$($expectedDocs -join '/')）"
                    }
                    $extra = @($docNos | Where-Object { $expectedDocs -notcontains $_ })
                    if ($extra.Count -gt 0) { $notes += "另引用了 $($extra -join ', ')" }
                }
                foreach ($word in @($item.expect.mention)) {
                    if ($squashed -notmatch [regex]::Escape((Squash $word))) { $fail += "答案里没提到 '${word}'" }
                }
            }
        } else {
            if (-not $refused) { $fail += '本该拒答却作答了' }
        }
    }
    if ($null -eq $trace) { $fail += '账本里没找到这次的记录' }

    $gap = $item.knownGap
    $ok = ($fail.Count -eq 0)
    if ($ok) {
        $flag = '[OK]  '
        $reason = '按预期'
        if ($notes.Count -gt 0) { $reason = ($notes -join '；') }
        if ($gap) { $reason += '｜已登记缺口，本次通过——可以从题集里去掉这条登记' }
    } elseif ($gap) {
        $flag = '[GAP] '
        $reason = ($fail -join '；') + "｜已登记缺口：$gap"
    } else {
        $flag = '[FAIL]'
        $reason = ($fail -join '；')
    }
    $scoreText = if ($trace -and $null -ne $trace.bestScore) { ('{0:N3}' -f $trace.bestScore) } else { '—' }
    $expectText = if ($item.expect.permission) { '应拒答·权限' } else { '应拒答' }
    if ($item.expect.outcome -eq 'answered') { $expectText = '应作答' }

    # 题型一列不是装饰：P3 的题集靠它归类失败——"跨文档答不全"和"表格行没召回"要改的东西完全不同。
    $kind = if ($item.expect.reasoning) { $item.expect.reasoning } else { '-' }
    Write-Host ("{0} {1,-5} {2,-7} {3,-7} {4,-8} {5,-6} {6,-8} {7,-17} {8}" -f $flag, $item.id, $item.domain,
        $item.asker, $expectText, $actual, $scoreText, $kind, $reason)

    $results += [pscustomobject]@{
        id = $item.id; domain = $item.domain; asker = $item.asker
        expect = $item.expect.outcome; actual = $actual; ok = $ok; gap = [bool]$gap
        bestScore = $(if ($trace) { $trace.bestScore } else { $null })
        permission = [bool]$item.expect.permission
        reasoning = $kind
        fail = ($fail -join '；'); note = ($notes -join '；')
    }
}

# ---------- 阈值校准 ----------
$answerable = @($results | Where-Object { $_.expect -eq 'answered' -and -not $_.permission -and $null -ne $_.bestScore })
$unanswerable = @($results | Where-Object { $_.expect -eq 'refused' -and -not $_.permission -and $null -ne $_.bestScore })
$current = @((Call 'GET' '/observe/traces?limit=1' $tokens.admin $null).Json.traces)
$threshold = $null
if ($current.Count -gt 0 -and $current[0]) {
    $detail = (Call 'GET' "/observe/traces/$($current[0].id)" $tokens.admin $null).Json
    $threshold = $detail.answerMinScore
}

Write-Host "`n---------- 阈值校准（ANSWER_MIN_SCORE） ----------"
if ($answerable.Count -gt 0 -and $unanswerable.Count -gt 0) {
    $minA = ($answerable | Sort-Object bestScore | Select-Object -First 1)
    $maxU = ($unanswerable | Sort-Object bestScore -Descending | Select-Object -First 1)
    Write-Host ("应作答 {0} 题：最低分 {1:N3}（{2}）" -f $answerable.Count, $minA.bestScore, $minA.id)
    Write-Host ("库里没有 {0} 题：最高分 {1:N3}（{2}）" -f $unanswerable.Count, $maxU.bestScore, $maxU.id)
    Write-Host '无权限题不参与校准：它们的分数是权限过滤之后剩下的，不代表"该不该作答"'
    if ($threshold) { Write-Host ("当前阈值：{0}" -f $threshold) }
    if ($maxU.bestScore -lt $minA.bestScore) {
        $suggested = [math]::Round(($maxU.bestScore + $minA.bestScore) / 2, 2)
        Write-Host ("可分区间：（{0:N3}, {1:N3}），建议阈值取中点 {2}" -f $maxU.bestScore, $minA.bestScore, $suggested)
    } else {
        Write-Host '两类分数重叠：库外问题里有比库内问题分还高的样本，单靠一个阈值分不开。'
        $below = @($answerable | Where-Object { $threshold -and $_.bestScore -lt $threshold })
        $above = @($unanswerable | Where-Object { $threshold -and $_.bestScore -ge $threshold })
        foreach ($r in $below) { Write-Host ("  被误挡的应作答题：{0}（{1:N3}）" -f $r.id, $r.bestScore) }
        foreach ($r in $above) { Write-Host ("  冒头的库外题：{0}（{1:N3}）" -f $r.id, $r.bestScore) }
        if ($threshold) {
            Write-Host ("当前阈值 {0} 卡在两者之间，是**保守**的一侧：宁可漏答，也不让库外问题被作答。" -f $threshold)
            Write-Host '下调阈值会让冒头的库外题变成"硬答"，上调会挡掉更多能答的题——所以根因不在阈值，'
            Write-Host '而在检索质量（分块粒度/纯向量召回）。改阈值前先改检索，否则只是换一种错法。'
        }
    }
} else {
    Write-Host '样本不足，无法给出区间'
}

$okCount = @($results | Where-Object { $_.ok }).Count
$gapCount = @($results | Where-Object { $_.gap -and -not $_.ok }).Count
Write-Host "`n通过 $okCount / $($results.Count) 题"
if ($gapCount -gt 0) {
    Write-Host "已登记缺口 $gapCount 题（如实记录，不用调阈值掩盖）："
    $results | Where-Object { $_.gap -and -not $_.ok } | ForEach-Object { Write-Host ("  {0}：{1}" -f $_.id, $_.fail) }
}
$failed = @($results | Where-Object { -not $_.ok -and -not $_.gap })
if ($failed.Count -gt 0) {
    Write-Host '未通过的题：'
    $failed | ForEach-Object { Write-Host ("  {0}：{1}" -f $_.id, $_.fail) }
}

# ---------- 按题型归类失败（P3 的产出就是这张表） ----------
# 每类失败的下一步不一样：跨文档答不全要动的是"一次只召回一份"，表格行没召回要动的是解析/分块，
# 拒答要动的是阈值与退出判断。混成一堆"30 题里有 8 题没过"，等于没分类。
if (@($results | Where-Object { $_.reasoning -ne '-' }).Count -gt 0) {
    Write-Host "`n---------- 失败按考察类型归类 ----------"
    $byKind = $results | Where-Object { $_.reasoning -ne '-' } |
        Group-Object reasoning | Sort-Object Name
    foreach ($group in $byKind) {
        $bad = @($group.Group | Where-Object { -not $_.ok })
        $line = "  {0,-17} 共 {1} 题，失败 {2} 题" -f $group.Name, $group.Count, $bad.Count
        if ($bad.Count -eq 0) { Write-Host ($line + ' —— 全部通过') -ForegroundColor Green }
        else { Write-Host $line -ForegroundColor Yellow }
        foreach ($row in $bad) {
            Write-Host ("      {0} [{1}]：{2}" -f $row.id, $row.actual, $row.fail)
        }
    }
    Write-Host ''
    Write-Host '失败题按"下一步该改什么"归类（同一题可能同时命中多条，按主因归类）：'
    Write-Host '  拒答·证据不足（INSUFFICIENT_EVIDENCE）→ 检索没召回全：拆解/多轮检索（S5/S6）'
    Write-Host '  拒答·输出截断或引用越界（OUTPUT_TRUNCATED / NO_CITATION）→ 提示词与退出判断（S7）'
    Write-Host '  作答了但没提到关键事实 → 证据拼不齐：多轮检索后要能把两处证据合起来（S6）'
    Write-Host '  没有引用期望文档 → 召回进了别的文档：域内区分度不够（S5 的拆解粒度）'
}
