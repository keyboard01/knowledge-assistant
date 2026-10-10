# T07 检索（文档仓 + 向量检索 → 证据）的验收脚本：可重复执行，只读 deploy\.env 取口令，不打印口令。
# 覆盖：未登录 401、参数校验、同文档多片段聚合、版本并列与冲突标记、权限过滤（含"命中被挡下"的提示）、
#       跨知识域、topN 截断、阈值过高、库外问题拒答、账本与内核登记一致性。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 脚本住在 tools\ 下，配置住在 deploy\ 下；两处都容忍，方便临时拷贝到别处执行
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
        SkipHttpErrorCheck = $true; TimeoutSec = 90
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

function Check($name, $ok, $detail) {
    $flag = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("{0} {1} —— {2}" -f $flag, $name, $detail)
}

function Search($token, $domain, $question, $topN, $minScore) {
    $body = @{ domainCode = $domain; question = $question }
    if ($topN) { $body.topN = $topN }
    if ($minScore) { $body.minScore = $minScore }
    return Call 'POST' '/retrieval/search' $token $body
}

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
Check '登录取令牌' ($adminToken -and $demoToken) 'admin 与 demo 均已登录'

# 1. 未登录不能检索
$anon = Search $null 'policy' '出差住宿费标准是多少'
Check '未登录检索被拒' ($anon.Status -eq 401) "HTTP $($anon.Status)"

# 2. 参数校验
$blank = Search $adminToken 'policy' '   '
Check '空问题返回 400' ($blank.Status -eq 400) "HTTP $($blank.Status)：$($blank.Json.error)"
$unknown = Search $adminToken 'nope' '随便问问'
Check '未知知识域返回 400' ($unknown.Status -eq 400) "HTTP $($unknown.Status)：$($unknown.Json.error)"

# 3. 管理员检索制度域：版本冲突 + 同文档片段聚合
$admin = Search $adminToken 'policy' '出差住宿费标准是多少' 5
$top = $admin.Json.evidences[0]
Check '命中正确的制度文档' ($top.docNo -eq 'QM-ZD-2026-003') "rank1 = $($top.docNo) $($top.version)（$($top.title)）"
Check '现行版本排在已废止之前' ($top.version -eq 'V3.2' -and $admin.Json.evidences[1].version -eq 'V2.8') (($admin.Json.evidences | ForEach-Object { "$($_.version)" }) -join ' > ')
Check '标记版本冲突（同编号两个版本并列保留）' ($admin.Json.diagnostics.versionConflict -eq $true) "versionConflict=$($admin.Json.diagnostics.versionConflict)"
Check '同文档多片段聚合成一条证据' ($top.fragments.Count -ge 2) "rank1 片段数 = $($top.fragments.Count)"
Check '片段保留 chunkId 与原文' (($top.fragments[0].chunkId) -and ($top.fragments[0].text -match '住宿')) "chunkId=$($top.fragments[0].chunkId)，正文前 20 字：$($top.fragments[0].text.Substring(0, [Math]::Min(20, $top.fragments[0].text.Length)))"
Check '正文不含内核注入的元数据块' (($admin.Raw -notmatch '<document_metadata>')) '响应里没有 document_metadata'
Check '账本记录过滤与耗时' ($admin.Json.diagnostics.elapsedMs -ge 0 -and $admin.Json.diagnostics.droppedUnauthorized -eq 0) "elapsed=$($admin.Json.diagnostics.elapsedMs)ms kernelHits=$($admin.Json.diagnostics.kernelHits)"

# 4. 权限过滤：demo 问秘密级文档
$secret = Search $demoToken 'policy' '2026年度薪酬结构调整方案的调薪幅度是多少' 5
$secretDocNos = @($secret.Json.evidences | ForEach-Object { $_.docNo })
Check 'demo 检索秘密级文档：结果里没有该文档' ($secretDocNos -notcontains 'QM-ZD-2026-021') "命中文档：$($secretDocNos -join ', ')"
Check 'demo 的响应里不出现秘密文档标题与正文' (($secret.Raw -notmatch '薪酬') -and ($secret.Raw -notmatch '调薪')) '响应全文不含"薪酬/调薪"'
# 用户的可见结果里只有低相关的公开片段，但必须明确告诉他"更相关的被权限挡了"，
# 否则他会以为"库里没有"，而不是"我没权限"。同时不能退化成"没找到"。
# 这条提示走 accessNotice（面向用户），不混在 hint（面向排障）里。
Check '提示是"没权限"而不是"没找到"' (($secret.Json.accessNotice -match '无权访问') -and ($secret.Raw -notmatch '没有找到') -and ($secret.Json.insufficient -eq $true)) "insufficient=$($secret.Json.insufficient) accessNotice=$($secret.Json.accessNotice)"
Check '排障用的 hint 与用户看到的提示分开' ($secret.Json.hint -notmatch '无权访问') "hint=$($secret.Json.hint)"
Check '账本记下被权限过滤的命中数' ($secret.Json.diagnostics.droppedUnauthorized -ge 1) "droppedUnauthorized=$($secret.Json.diagnostics.droppedUnauthorized)"

# 5. 管理员问同一份秘密文档：应当能搜到（对照组）
$secretAdmin = Search $adminToken 'policy' '2026年度薪酬结构调整方案的调薪幅度是多少' 5
$secretAdminDocNos = @($secretAdmin.Json.evidences | ForEach-Object { $_.docNo })
Check '管理员能检索到该文档（对照组）' ($secretAdminDocNos -contains 'QM-ZD-2026-021') "命中文档：$($secretAdminDocNos -join ', ')"

# 6. 跨知识域：维修域
$repair = Search $adminToken 'repair' '空压机 GA-75 多久保养一次' 3
Check '维修域检索命中保养作业指导书' (@($repair.Json.evidences | ForEach-Object { $_.docNo }) -contains 'QM-WX-GA75-2026') "命中文档：$((@($repair.Json.evidences | ForEach-Object { $_.docNo })) -join ', ')"

# 7. 候选不足：topN=1 / 阈值过高 / 无关问题
$narrow = Search $adminToken 'policy' '出差住宿费标准是多少' 1
Check 'topN 生效' ($narrow.Json.evidences.Count -eq 1) "证据数 = $($narrow.Json.evidences.Count)"
$strict = Search $adminToken 'policy' '出差住宿费标准是多少' 5 0.95
# P2 起是两路召回：minScore 只管向量那一路，关键词通道有自己的下限（trigram 与余弦不是一个量纲，
# 把 0.95 套到关键词上等于把它关掉——而它正是"精确词"那条命脉）。
# 所以"阈值过高"不再等于 0 条证据，而是"留下来的都够不上作答线"，后者才是这条要钉住的东西；
# 顺带把"作答线必须高于召回线"也比一遍，防止有人把两个数改反。
Check '阈值过高时标记证据不足' (($strict.Json.insufficient -eq $true) -and ($strict.Json.diagnostics.bestScore -lt $strict.Json.diagnostics.answerMinScore)) "证据数 = $($strict.Json.evidences.Count)，向量最高分 = $($strict.Json.diagnostics.bestScore) < 作答下限 $($strict.Json.diagnostics.answerMinScore)，hint=$($strict.Json.hint)"
$unrelated = Search $adminToken 'policy' '公司食堂今天的菜单是什么' 5
Check '库外问题：不编造，明确标证据不足' (($unrelated.Json.insufficient -eq $true) -and ($unrelated.Json.hint -match '相关性偏低')) "insufficient=$($unrelated.Json.insufficient) 最高分=$($unrelated.Json.diagnostics.bestScore) hint=$($unrelated.Json.hint)"

# 9. 作答下限被记录在账本里：T11 的 30 题集就是靠它校准的
Check '账本记下作答下限' ($admin.Json.diagnostics.answerMinScore -gt $admin.Json.diagnostics.minScore) "召回下限=$($admin.Json.diagnostics.minScore) 作答下限=$($admin.Json.diagnostics.answerMinScore)"

# 8. 过滤账本：droppedUnknown 为 0 说明内核与登记表一致
Check '内核与本地登记一致（无未登记命中）' ($admin.Json.diagnostics.droppedUnknown -eq 0) "droppedUnknown=$($admin.Json.diagnostics.droppedUnknown)"
