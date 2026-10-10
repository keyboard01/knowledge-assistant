# T10 观测的验收脚本（AC-P1-011）：可重复执行，只读 deploy\.env 取口令，不打印口令。
# 覆盖：账本权限（未登录 401 / viewer 403）、询问后落账、问题与可见范围原文、阈值与内核命中、
#       命中片段明细可反查、耗时分解、拒答也落账且 token=0、费用（未配=null 不是 0）、
#       单价快照（改价不改历史）、汇总口径自洽、不存在的记录 404。
# 注意：本脚本会真调用生成模型并写入运行记录（记录本身就是证据），也会临时改一次单价配置并在末尾清掉。
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
        SkipHttpErrorCheck = $true; TimeoutSec = 180
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 5); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

function Check($name, $ok, $detail) {
    $flag = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("{0} {1} —— {2}" -f $flag, $name, $detail)
}

function Ask($token, $domain, $question) {
    return Call 'POST' '/generation/answer' $token @{ domainCode = $domain; question = $question; topN = 5 }
}

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
Check '登录取令牌' ($adminToken -and $demoToken) 'admin 与 demo 均已登录'

# 1. 账本权限：它比被提问的知识库更敏感（里面有全部人的提问原话）
Check '未登录看不到账本' ((Call 'GET' '/observe/traces' $null $null).Status -eq 401) 'HTTP 401'
$viewer = Call 'GET' '/observe/traces' $demoToken $null
Check 'viewer 看不到账本' ($viewer.Status -eq 403) "HTTP $($viewer.Status)：$($viewer.Json.error)"

# 2. 提问后落账
$before = (Call 'GET' '/observe/summary' $adminToken $null).Json.total
$answer = Ask $adminToken 'policy' '出差住宿费标准是多少'
Check '库内问题正常作答' (($answer.Status -eq 200) -and ($answer.Json.refused -eq $false)) "refused=$($answer.Json.refused)"

$listed = Call 'GET' '/observe/traces?limit=1' $adminToken $null
$row = $listed.Json.traces[0]
Check '问答后账本多了一条' (($listed.Json.summary.total) -eq ($before + 1)) "提问前 $before → 提问后 $($listed.Json.summary.total)"
Check '账本记下问题原话与提问人' (($row.question -eq '出差住宿费标准是多少') -and ($row.userName -eq 'admin') -and ($row.domainCode -eq 'policy')) "user=$($row.userName) domain=$($row.domainCode) question=$($row.question)"
Check '账本记下 token 与总耗时' (($row.promptTokens -gt 0) -and ($row.completionTokens -gt 0) -and ($row.totalElapsedMs -gt 0)) "token=$($row.promptTokens)/$($row.completionTokens) 耗时=$($row.totalElapsedMs)ms"
Check '账本记下证据与引用数' (($row.evidenceCount -ge 1) -and ($row.citedCount -ge 1)) "证据=$($row.evidenceCount) 引用=$($row.citedCount)"
Check '账本记下最高分' ($null -ne $row.bestScore) "最高分=$($row.bestScore)"

# 3. 明细：过滤条件、阈值、内核命中、片段
$detail = (Call 'GET' "/observe/traces/$($row.id)" $adminToken $null).Json
Check '明细记下当时的可见范围（过滤条件）' ($detail.accessFilter -match 'roles=\[admin\]' -and $detail.accessFilter -match 'document_acl') "accessFilter=$($detail.accessFilter.Substring(0, [Math]::Min(60, $detail.accessFilter.Length)))..."
# 阈值不写死：P1 的 0.45 在 P2 被抬到 0.51（细粒度分块后整个分布上移），写死就等于每次调阈值都要改脚本，
# 而且改错了也看不出来。改成"账本记的必须等于当次检索真正用的"——这才是有信息量的那条断言。
$live = (Call 'POST' '/retrieval/search' $adminToken @{ domainCode = 'policy'; question = '出差住宿费标准是多少'; topN = 5 }).Json.diagnostics
Check '明细记下的阈值与当次检索一致' ((($detail.minScore -eq $live.minScore) -and ($detail.answerMinScore -eq $live.answerMinScore)) -and ($detail.kernelTopN -ge 5)) "账本 召回=$($detail.minScore)/作答=$($detail.answerMinScore)；当次检索 召回=$($live.minScore)/作答=$($live.answerMinScore)；内核topN=$($detail.kernelTopN)"
Check '明细记下内核命中与丢弃计数' ($detail.kernelHits -ge 1) "内核命中=$($detail.kernelHits) 无权限=$($detail.droppedUnauthorized) 未登记=$($detail.droppedUnknown)"
Check '耗时分解自洽：检索 + 模型 = 合计' (($detail.retrievalElapsedMs + $detail.generationElapsedMs) -eq $detail.row.totalElapsedMs) "检索 $($detail.retrievalElapsedMs) + 模型 $($detail.generationElapsedMs) = $($detail.row.totalElapsedMs)ms"
# 按 code/key 找配置，不按数组下标——分组的增删不该让这条断言失效
$llmGroup = (Call 'GET' '/settings' $adminToken $null).Json.groups | Where-Object { $_.code -eq 'llm' }
$configuredModel = ($llmGroup.fields | Where-Object { $_.key -eq 'model' }).value
Check '明细记下模型名' ($detail.model -eq $configuredModel) "账本 model=$($detail.model)，配置 model=$($configuredModel)"

$fragments = @($detail.evidences)
Check '命中片段逐条落账（每条带 chunkId 与分数）' (($fragments.Count -ge 1) -and (@($fragments | Where-Object { -not $_.chunkId -or $null -eq $_.score }).Count -eq 0)) "片段 $($fragments.Count) 条"
# A3：片段还要带自然键 chunkIndex。chunk_id 是代理键（重解析可能换），(document_id, chunk_index) 是
# document_chunk 的唯一约束，不会漂移——账本将来"点回原文"要靠它，缺了就只剩一条会变的 id。
$withIndex = @($fragments | Where-Object { $null -ne $_.chunkIndex })
Check '片段带自然键 chunkIndex（回查原文不依赖会变的 chunk_id）' ($withIndex.Count -eq $fragments.Count) `
    "带序号 $($withIndex.Count)/$($fragments.Count) 条；示例 chunkId=$($fragments[0].chunkId) → chunkIndex=$($fragments[0].chunkIndex)"
Check '片段上标了哪几条被引用' (@($fragments | Where-Object { $_.cited }).Count -ge 1) "已引用 $(@($fragments | Where-Object { $_.cited }).Count) 条"

# 4. 账本里的片段能反查回检索接口的候选：账本不能自成一世界
$retr = (Call 'POST' '/retrieval/search' $adminToken @{ domainCode = 'policy'; question = '出差住宿费标准是多少'; topN = 5 }).Json
$candidate = @{}
foreach ($e in $retr.evidences) { foreach ($f in $e.fragments) { $candidate[$f.chunkId] = $true } }
$unmatched = @($fragments | Where-Object { -not $candidate.ContainsKey($_.chunkId) })
Check '账本片段能反查回检索候选' ($unmatched.Count -eq 0) "对不上 $($unmatched.Count) 条，候选 $($candidate.Count) 条"

# 5. 拒答也落账，而且不该花 token
$unrelated = Ask $adminToken 'policy' '公司食堂今天的菜单是什么'
$refusedRow = (Call 'GET' '/observe/traces?limit=1' $adminToken $null).Json.traces[0]
Check '拒答的请求同样落账' (($refusedRow.refused -eq $true) -and ($refusedRow.insufficient -eq $true)) "refused=$($refusedRow.refused) insufficient=$($refusedRow.insufficient)"
Check '拒答账本记 modelCalled=false 且 token=0/0' (($refusedRow.modelCalled -eq $false) -and ($refusedRow.promptTokens -eq 0) -and ($refusedRow.completionTokens -eq 0)) "modelCalled=$($refusedRow.modelCalled) token=$($refusedRow.promptTokens)/$($refusedRow.completionTokens)"
Check '拒答账本记下规则码' ($refusedRow.refusalRule -eq $unrelated.Json.diagnostics.refusalRule) "规则=$($refusedRow.refusalRule)"

# 6. 费用：未配单价记 null（不是 0），配了才计费，改价不改历史
$unpricedRow = (Call 'GET' '/observe/traces?limit=1' $adminToken $null).Json.traces[0]
Check '未配单价时费用为空而不是 0' (($null -eq $unpricedRow.costTotal) -and ($unpricedRow.priced -eq $false)) "costTotal=$($unpricedRow.costTotal) priced=$($unpricedRow.priced)"

$TEST_PROMPT_PRICE = '2'
$TEST_COMPLETION_PRICE = '8'
$pricedAnswer = $null
$pricedRow = $null
try {
    Call 'PUT' '/settings/llm' $adminToken @{ values = @{ promptPricePer1k = $TEST_PROMPT_PRICE; completionPricePer1k = $TEST_COMPLETION_PRICE } } | Out-Null
    $pricedAnswer = Ask $adminToken 'policy' '出差住宿费标准是多少'
    $pricedRow = (Call 'GET' '/observe/traces?limit=1' $adminToken $null).Json.traces[0]
    $expected = [math]::Round($pricedAnswer.Json.diagnostics.promptTokens / 1000 * [double]$TEST_PROMPT_PRICE +
                              $pricedAnswer.Json.diagnostics.completionTokens / 1000 * [double]$TEST_COMPLETION_PRICE, 4)
    Check '配上单价后按 token × 单价计费' (($pricedRow.priced -eq $true) -and ([math]::Abs($pricedRow.costTotal - $expected) -le 0.001)) "cost=$($pricedRow.costTotal) 期望≈$expected"

    # 改价之后再问一次：新账用新价，老账必须一个字都不变
    Call 'PUT' '/settings/llm' $adminToken @{ values = @{ promptPricePer1k = '4'; completionPricePer1k = '16' } } | Out-Null
    Ask $adminToken 'policy' '出差住宿费标准是多少' | Out-Null
    $reListed = (Call 'GET' "/observe/traces/$($pricedRow.id)" $adminToken $null).Json
    Check '改价不改历史账本（单价快照生效）' ($reListed.row.costTotal -eq $pricedRow.costTotal) "改价后该账仍是 $($reListed.row.costTotal) 元，快照单价 $($reListed.promptPricePer1k)/$($reListed.completionPricePer1k)"
} finally {
    foreach ($field in 'promptPricePer1k', 'completionPricePer1k') {
        Call 'DELETE' "/settings/llm/$field" $adminToken $null | Out-Null
    }
}
Check '单价配置已复原（清空）' ($null -eq (Call 'GET' '/observe/price' $adminToken $null).Json.promptPer1k) '单价已清空，费用恢复"不记"'

# 7. 汇总口径自洽
$all = (Call 'GET' '/observe/traces?limit=500' $adminToken $null).Json
$sum = $all.summary
Check '汇总的总数与明细条数一致' ($sum.total -eq @($all.traces).Count) "汇总 $($sum.total) 条，列表 $((@($all.traces)).Count) 条"
Check '汇总把"计费"与"未计费"分开且加起来等于总数' (($sum.pricedCount + $sum.unpricedCount) -eq $sum.total) "已计费 $($sum.pricedCount) + 未计费 $($sum.unpricedCount) = $($sum.total)"
$refusedOnly = (Call 'GET' '/observe/summary?refused=true' $adminToken $null).Json
Check '拒答筛选的汇总与总数吻合' ($refusedOnly.refused -eq $refusedOnly.total) "拒答筛选：$($refusedOnly.total) 条，其中拒答 $($refusedOnly.refused) 条"
Check '拒答率与明细能对上' ([math]::Abs($sum.refuseRate - [math]::Round($sum.refused * 100 / $sum.total, 2)) -le 0.01) "拒答率 $($sum.refuseRate)%（$($sum.refused)/$($sum.total)）"

# 8. 不存在的记录
Check '查不存在的运行记录返回 404' ((Call 'GET' '/observe/traces/99999999' $adminToken $null).Status -eq 404) 'HTTP 404'
