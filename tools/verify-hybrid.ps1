# T18 验收脚本（AC-P2-004 / AC-P2-005 / AC-P2-007）：可重复执行，只读 deploy\.env 取口令，不打印口令。
#
# 三条各自要回答一个问题：
#   AC-P2-004 关键词通道也挡得住 —— 秘密文档在"关键词确实能命中"的前提下，候选、响应与账本里都不出现
#   AC-P2-005 短问题撞定义句      —— Q03 这类"问题短、答案也短"的题能作答，且引用落在真正含答案的片段上
#   AC-P2-007 账本能定责          —— 两路各命中了什么、融合前排第几，出问题时能看出是哪一路失效
#
# AC-P2-004 的关键是**先把前提做出来**。只测"响应里没有秘密文档"是不够的：一个把所有候选都丢光的
# 实现同样能过，而那正是另一类要防的错。所以先用 SQL 直接在库上证明"关键词通道确实能命中它"，
# 再看接口与账本里有没有它——测的是"挡得住"，不是"没命中"。
#
# 泄露词不手写，从题集 Q26 的 noLeak 里取：两个脚本判"什么算泄露"必须是同一份清单，
# 各写各的早晚会出现"这个脚本说泄漏了、那个说没事"。
#
# 注意：本脚本会真调用生成模型 3 次（一条无权限题 + Q03 + 两路名次那条），并按结果写入运行记录。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$envPath = @((Join-Path $PSScriptRoot '..\deploy\.env'), (Join-Path $PSScriptRoot '.env')) |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $envPath) { throw '找不到 deploy\.env，无法取口令' }
$envFile = Get-Content -LiteralPath $envPath -Encoding UTF8
function EnvValue($key) {
    (($envFile | Where-Object { $_ -match "^\s*$key=" } | Select-Object -First 1) -replace "^\s*$key=", '').Trim()
}

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri = "http://127.0.0.1:9090/api$path"; Method = $method; Headers = $headers
        SkipHttpErrorCheck = $true; TimeoutSec = 300
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

# 库内事实一律直连数据库取，不经过被测的接口——接口说"没漏"和库里真的没写进去是两件事。
function Sql([string]$query) {
    $out = & docker exec assistant-pg psql -U assistant -d assistant -t -A -F '|' -c $query 2>&1
    if ($LASTEXITCODE -ne 0) { throw "psql 查询失败：$out`nSQL: $query" }
    # 只返回一行时 PowerShell 会把数组拆成标量。调用方一律用 @() 包住，别直接下标。
    return @($out | Where-Object { $_ -ne '' })
}

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++ } else { $script:fail++ }
    $flag = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("{0} {1} —— {2}" -f $flag, $name, $detail)
}

# 题集是"什么算泄露"的唯一来源：Q26 是无权限题里信息量最大的一条（正文带具体数字）。
$setPath = Join-Path $PSScriptRoot 'questions\p1-30.json'
$questionSet = (Get-Content -LiteralPath $setPath -Raw -Encoding UTF8 | ConvertFrom-Json).questions
$q26 = $questionSet | Where-Object { $_.id -eq 'Q26' } | Select-Object -First 1
if (-not $q26) { throw '题集里找不到 Q26（无权限题），无法取泄露词清单' }
$secretQuestion = $q26.question
$leakWords = @($q26.expect.noLeak | Where-Object { $_ -and ($_ -ne $q26.question) })

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
Check '登录取令牌' ($adminToken -and $demoToken) 'admin 与 demo 均已登录'

# ---------- AC-P2-004：关键词通道也挡得住 ----------
Write-Host "`n---------- AC-P2-004 关键词通道也挡得住（demo 无秘密文档权限） ----------"
Write-Host "  前提：题目来自题集 Q26；泄露词清单也来自它：$($leakWords -join '、')"

# 前提：这条问题在**关键词通道**上确实命中秘密文档。用库直查，参数与实现一致（word_similarity >= 0.12）。
$keywordReachable = [int](@(Sql "select count(*) from document_chunk c join document d on d.id = c.document_id where d.domain_code = 'policy' and d.doc_no = 'QM-ZD-2026-021' and word_similarity('$secretQuestion', c.content) >= 0.12")[0])
Check '前提成立：关键词通道能命中秘密文档（否则测的是"没命中"，不是"挡住了"）' ($keywordReachable -gt 0) `
    "关键词命中秘密文档的片段数=$keywordReachable"

$demoSearch = Call 'POST' '/retrieval/search' $demoToken @{ domainCode = 'policy'; question = $secretQuestion; topN = 5 }
Check '检索接口返回 200' ($demoSearch.Status -eq 200) "HTTP $($demoSearch.Status)"
$searchLeaks = @($leakWords | Where-Object { $demoSearch.Raw -match [regex]::Escape($_) })
Check 'AC-P2-004 候选里不出现秘密文档编号及其专属内容' ($searchLeaks.Count -eq 0) `
    $(if ($searchLeaks.Count) { "响应里出现了：$($searchLeaks -join '、')" } else { "响应全文不含：$($leakWords -join '、')" })
Check '明确告诉用户是权限问题（不是"没找到"）' `
    (($demoSearch.Json.accessNotice -match '无权访问') -and ($demoSearch.Raw -notmatch '没有找到')) `
    "accessNotice=$($demoSearch.Json.accessNotice)"
Check '账本记下被挡下的命中数' ($demoSearch.Json.diagnostics.droppedUnauthorized -ge 1) `
    "droppedUnauthorized=$($demoSearch.Json.diagnostics.droppedUnauthorized)"

$demoAnswer = Call 'POST' '/generation/answer' $demoToken @{ domainCode = 'policy'; question = $secretQuestion; topN = 5 }
Check '问答接口返回 200' ($demoAnswer.Status -eq 200) "HTTP $($demoAnswer.Status)"
$answerLeaks = @($leakWords | Where-Object { $demoAnswer.Raw -match [regex]::Escape($_) })
Check 'AC-P2-004 问答响应里也不出现秘密文档编号及其专属内容' ($answerLeaks.Count -eq 0) `
    $(if ($answerLeaks.Count) { "响应里出现了：$($answerLeaks -join '、')" } else { '响应全文不含泄露词' })

# 账本明细：demo 名下所有制度域记录里，秘密文档一条都不该有
$demoTraces = [int](@(Sql "select count(*) from qa_trace where user_name = 'demo' and domain_code = 'policy'")[0])
Check '账本里确实有 demo 的制度域记录（否则下面那条查的是空集）' ($demoTraces -ge 1) "demo 制度域记录=$demoTraces 条"
$leakedRows = [int](@(Sql "select count(*) from qa_trace_evidence e join qa_trace t on t.id = e.trace_id where t.user_name = 'demo' and t.domain_code = 'policy' and e.doc_no = 'QM-ZD-2026-021'")[0])
Check 'AC-P2-004 账本明细里秘密文档一条都没有' ($leakedRows -eq 0) "漏出的行数=$leakedRows"

# ---------- AC-P2-005：短问题撞定义句 ----------
Write-Host "`n---------- AC-P2-005 短问题撞定义句（Q03：哪些城市算一线城市） ----------"
$definitionQuestion = '哪些城市算一线城市'
$q03Search = Call 'POST' '/retrieval/search' $adminToken @{ domainCode = 'policy'; question = $definitionQuestion; topN = 5 }
Check '检索判为"证据足够"' ($q03Search.Json.insufficient -eq $false) `
    "insufficient=$($q03Search.Json.insufficient) 最高分=$($q03Search.Json.diagnostics.bestScore)（作答下限 $($q03Search.Json.diagnostics.answerMinScore)）"

# 定义句可能不是向量第一名（这正是"短问题撞长块"的原始症状），所以按内容找，而不是赌它排第一
$definition = $null
$definitionRank = 0
foreach ($evidence in $q03Search.Json.evidences) {
    foreach ($fragment in $evidence.fragments) {
        if (($fragment.text -match '北京') -and ($fragment.text -match '深圳')) { $definition = $fragment; $definitionRank = $evidence.rank; break }
    }
    if ($definition) { break }
}
Check '检索结果里有定义句片段（同段含北京与深圳）' ($null -ne $definition) `
    $(if ($definition) { "位于第 $definitionRank 条证据，chunkId=$($definition.chunkId)：$($definition.text)" } else { "前 5 条证据里都没有定义句" })
if ($definition) {
    Check '定义句片段带页码且是第 1 页' ($definition.pageFrom -eq 1) "pageFrom=$($definition.pageFrom) pageTo=$($definition.pageTo)"
    Check '定义句片段由向量通道召回' ($definition.vectorRank -gt 0) `
        "vectorRank=$($definition.vectorRank) keywordRank=$($definition.keywordRank) keywordScore=$($definition.keywordScore)"
    $definitionChunk = [int]$definition.chunkId
    $dbDefinition = [int](@(Sql "select count(*) from document_chunk where id = $definitionChunk and content like '%北京%' and page_from = 1")[0])
    Check '库内独立核对：该 chunkId 的正文含"北京"且页码为 1' ($dbDefinition -eq 1) "chunkId=$definitionChunk"
}

$q03 = Call 'POST' '/generation/answer' $adminToken @{ domainCode = 'policy'; question = $definitionQuestion; topN = 5 }
Check 'Q03 被放行作答（不是拒答）' ($q03.Status -eq 200 -and -not $q03.Json.refused) `
    "refused=$($q03.Json.refused) rule=$($q03.Json.diagnostics.refusalRule)"
$answerText = ($q03.Json.answer -replace '\s', '')
Check '答案里四个一线城市齐全' (($answerText -match '北京') -and ($answerText -match '上海') -and ($answerText -match '广州') -and ($answerText -match '深圳')) `
    "答案=$($q03.Json.answer)"

$citedList = (@($q03.Json.citations | ForEach-Object { $_.chunkId }) | Where-Object { $_ -match '^\d+$' }) -join ','
$citedHasDefinition = 0
if ($citedList) {
    $citedHasDefinition = [int](@(Sql "select count(*) from document_chunk where id in ($citedList) and content like '%北京%'")[0])
}
Check 'AC-P2-005 引用落在真正含答案的片段上（被引用的 chunk 正文含"北京"）' ($citedHasDefinition -ge 1) `
    "被引用的 chunkId=$citedList；其中含北京=$citedHasDefinition 条"

# ---------- AC-P2-007：账本能定责 ----------
Write-Host "`n---------- AC-P2-007 账本能定责（两路名次） ----------"
$twoChannelQuestion = '一线城市的住宿标准是多少钱一晚'
$two = Call 'POST' '/retrieval/search' $adminToken @{ domainCode = 'policy'; question = $twoChannelQuestion; topN = 5 }
$diag = $two.Json.diagnostics
$hasFields = ($null -ne $diag.keywordMinScore) -and ($null -ne $diag.vectorHits) -and ($null -ne $diag.keywordHits) -and ($null -ne $diag.bestKeywordScore)
Check '诊断里有向量/关键词两路的计数与下限' $hasFields `
    "召回下限=$($diag.minScore) 作答下限=$($diag.answerMinScore)/$($diag.keywordMinScore)(关键词) 向量命中=$($diag.vectorHits) 关键词命中=$($diag.keywordHits) 关键词最高分=$($diag.bestKeywordScore)"
Check '这条问题两路都召回到了候选（两路都失效时这条会假过，所以先钉住它）' ($diag.vectorHits -ge 1 -and $diag.keywordHits -ge 1) `
    "向量=$($diag.vectorHits) 关键词=$($diag.keywordHits)"

$twoAnswer = Call 'POST' '/generation/answer' $adminToken @{ domainCode = 'policy'; question = $twoChannelQuestion; topN = 5 }
Check '问答接口返回 200' ($twoAnswer.Status -eq 200) "HTTP $($twoAnswer.Status)"

$latestId = @(Sql "select max(id) from qa_trace where user_name = 'admin' and question = '$twoChannelQuestion'")[0]
Check '这次问答已写进账本' ($latestId -match '^\d+$') "trace id=$latestId"
$detail = Call 'GET' "/observe/traces/$latestId" $adminToken $null
$bothChannels = @($detail.Json.evidences | Where-Object { $_.vectorRank -gt 0 -and $_.keywordRank -gt 0 })
$vectorOnly = @($detail.Json.evidences | Where-Object { $_.vectorRank -gt 0 -and $_.keywordRank -eq 0 })
Check '账本明细能看出"哪一路命中了什么"' ($bothChannels.Count -ge 1) `
    "两路都命中=$($bothChannels.Count) 条；只向量=$($vectorOnly.Count) 条；共 $($detail.Json.evidences.Count) 条"
Check '账本明细同时给出融合后的最终名次' (@($detail.Json.evidences | Where-Object { $_.rank -ge 1 }).Count -eq $detail.Json.evidences.Count) `
    "证据行名次=$((@($detail.Json.evidences | ForEach-Object { $_.rank })) -join ',')"
if ($bothChannels.Count -ge 1) {
    $sample = $bothChannels[0]
    Check '样例片段的两路名次与关键词分都非零' ($sample.keywordScore -gt 0) `
        "rank=$($sample.rank) 融合前排第几：向量 $($sample.vectorRank)、关键词 $($sample.keywordRank)；关键词分=$($sample.keywordScore)"
}

# 库内独立核对：名次是落在账本表里的，不是接口现算的
$bothRows = [int](@(Sql "select count(*) from qa_trace_evidence where trace_id = $latestId and vector_rank > 0 and keyword_rank > 0")[0])
$keywordRows = [int](@(Sql "select count(*) from qa_trace_evidence where trace_id = $latestId and keyword_rank > 0")[0])
Check 'AC-P2-007 账本表里确实落了两路名次' ($bothRows -ge 1 -and $keywordRows -ge 1) `
    "两路都命中=$bothRows 行；关键词召回到=$keywordRows 行"

# ---------- 汇总 ----------
Write-Host ""
Write-Host ("通过 {0} / {1} 项检查" -f $script:pass, ($script:pass + $script:fail))
if ($script:fail -gt 0) { exit 1 }