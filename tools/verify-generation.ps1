# T08 生成的验收脚本：可重复执行，只读 deploy\.env 取口令，不打印口令。
# 覆盖：未登录 401、参数校验、库内问题带引用作答、引用能反查回检索证据、编号不越界、
#       版本冲突并列、库外问题拒答（且不花 token）、无权限问题拒答且不泄露。
# 注意：本脚本会真的调用生成模型，单次约几秒、会消耗 token；管理员与 demo 两个会话都会被用到。
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

function Ask($token, $domain, $question) {
    return Call 'POST' '/generation/answer' $token @{ domainCode = $domain; question = $question; topN = 5 }
}

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
Check '登录取令牌' ($adminToken -and $demoToken) 'admin 与 demo 均已登录'

# 1. 未登录：默认拒绝
$anon = Ask $null 'policy' '出差住宿费标准是多少'
Check '未登录问答被拒' ($anon.Status -eq 401) "HTTP $($anon.Status)"

# 2. 参数校验：错误必须报错，不能伪装成"答不上来"
$blank = Ask $adminToken 'policy' '   '
Check '空问题返回 400' ($blank.Status -eq 400) "HTTP $($blank.Status)：$($blank.Json.error)"
$unknown = Ask $adminToken 'nope' '随便问问'
Check '未知知识域返回 400' ($unknown.Status -eq 400) "HTTP $($unknown.Status)：$($unknown.Json.error)"

# 3. 库内问题：必须给出带引用的答案
$gen = Ask $adminToken 'policy' '出差住宿费标准是多少'
Check '库内问题给出答案' (($gen.Status -eq 200) -and ($gen.Json.refused -eq $false) -and ($gen.Json.answer.Length -gt 20)) "HTTP $($gen.Status) refused=$($gen.Json.refused) 字数=$($gen.Json.answer.Length)"
Check '答案至少带 1 条引用（AC-P1-001）' ($gen.Json.citations.Count -ge 1) "引用数 = $($gen.Json.citations.Count)"
Check '每条引用都带编号、文档号、版本与 chunkId' (@($gen.Json.citations | Where-Object { $_.docNo -and $_.version -and $_.chunkId }).Count -eq $gen.Json.citations.Count) (($gen.Json.citations | ForEach-Object { "$($_.docNo)@$($_.version)" }) -join ', ')
Check '答案正文里标了引用编号' ($gen.Json.answer -match '\[\s*\d+\s*\]' -or $gen.Json.answer -match '【\s*\d+\s*】') "正文含方括号编号"
Check '答案不含内核注入的元数据块' ($gen.Raw -notmatch '<document_metadata>') '响应里没有 document_metadata'

# 4. 引用可核对 + 编号不越界：这是"引用指向真实存在的 chunk"的硬检查
$evidenceCount = $gen.Json.diagnostics.evidenceCount
$marks = [regex]::Matches($gen.Json.answer, '[\u3010\[]\s*(\d{1,3})\s*[\u3011\]]') | ForEach-Object { [int]$_.Groups[1].Value }
$maxMark = if ($marks) { ($marks | Measure-Object -Maximum).Maximum } else { 0 }
Check '答案里的引用编号不越界' (($maxMark -ge 1) -and ($maxMark -le $evidenceCount)) "最大编号=$maxMark 证据数=$evidenceCount"

$retr = Call 'POST' '/retrieval/search' $adminToken @{ domainCode = 'policy'; question = '出差住宿费标准是多少'; topN = 5 }
$chunkIds = @{}
foreach ($e in $retr.Json.evidences) { foreach ($f in $e.fragments) { $chunkIds[$f.chunkId] = $true } }
$unmatched = @($gen.Json.citations | Where-Object { -not $chunkIds.ContainsKey($_.chunkId) })
Check '引用能反查回检索证据的片段' ($unmatched.Count -eq 0) "未匹配引用数 = $($unmatched.Count)，候选片段 $($chunkIds.Count) 个"

# 5. 版本冲突：两个版本都要有引用，且正文并列说明
$versions = @($gen.Json.citations | ForEach-Object { $_.version })
Check '同编号的两个版本都带引用（AC-P1-004）' (($versions -contains 'V3.2') -and ($versions -contains 'V2.8')) ($versions -join ', ')
Check '正文并列说明两个版本' (($gen.Json.answer -match 'V3\.2') -and ($gen.Json.answer -match 'V2\.8')) '正文同时出现 V3.2 与 V2.8'

# 6. 账本：AC-P1-011 的 token 与耗时
$d = $gen.Json.diagnostics
Check '账本记录模型、token 与耗时' (($d.modelCalled -eq $true) -and ($d.promptTokens -gt 0) -and ($d.completionTokens -gt 0) -and ($d.elapsedMs -gt 0)) "模型=$($d.model) token=$($d.promptTokens)/$($d.completionTokens) 耗时=$($d.elapsedMs)ms"

# 7. 库外问题：拒答，且根本不该花掉一次模型调用
$unrelated = Ask $adminToken 'policy' '公司食堂今天的菜单是什么'
Check '库外问题：拒答而不是编造（AC-P1-002）' (($unrelated.Json.refused -eq $true) -and ($unrelated.Json.citations.Count -eq 0)) "refused=$($unrelated.Json.refused) 引用数=$($unrelated.Json.citations.Count)"
Check '拒答原因码可统计' ($unrelated.Json.diagnostics.refusalRule -eq 'INSUFFICIENT_EVIDENCE') "rule=$($unrelated.Json.diagnostics.refusalRule)"
Check '拒答文案明确说无法回答' ($unrelated.Json.answer -match '无法可靠回答') "answer=$($unrelated.Json.answer)"
Check '证据不足时不调用模型（省一次 token）' (($unrelated.Json.diagnostics.modelCalled -eq $false) -and ($unrelated.Json.diagnostics.promptTokens -eq 0)) "modelCalled=$($unrelated.Json.diagnostics.modelCalled) token=$($unrelated.Json.diagnostics.promptTokens)/$($unrelated.Json.diagnostics.completionTokens)"

# 8. 无权限：拒答，且响应全文不出现秘密文档的任何内容
$secret = Ask $demoToken 'policy' '2026年度薪酬结构调整方案的调薪幅度是多少'
Check 'demo 问无权限文档：拒答' (($secret.Json.refused -eq $true) -and ($secret.Json.citations.Count -eq 0)) "refused=$($secret.Json.refused)"
Check 'demo 的响应里不出现秘密文档内容' (($secret.Raw -notmatch '薪酬') -and ($secret.Raw -notmatch '调薪')) '响应全文不含"薪酬/调薪"'
Check '拒答里说明是权限原因而非没找到' (($secret.Json.answer -match '无权访问') -and ($secret.Raw -notmatch '没有找到')) "answer=$($secret.Json.answer)"

# 9. 跨知识域：维修域也能作答
$repair = Ask $adminToken 'repair' '空压机 GA-75 多久保养一次'
Check '维修域给出带引用的答案' (($repair.Json.refused -eq $false) -and ($repair.Json.citations.Count -ge 1)) "引用数 = $($repair.Json.citations.Count)，文档 = $(($repair.Json.citations | ForEach-Object { $_.docNo }) -join ', ')"
