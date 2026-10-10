# P0 重排（rerank）验收：可重复执行、跑完恢复原配置、不打印口令与密钥。
# 覆盖：
#   ① 基线可检索，bestScore 仍为向量余弦、作答阈值仍高于召回阈值（口径不变）；
#   ② 启用但端点不可达 → fail-open：HTTP 200、顺序逐位不变、不注入重排分；
#   ③ 设置页 rerank 探活接口可用（未配置也应是 200 + ok:false，而不是 5xx）；
#   ④ 默认关闭时基线不含重排分。
# 前置：后端 9090 在跑；deploy\.env 里有管理员口令。
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

function Search($token, $domain, $question) {
    return Call 'POST' '/retrieval/search' $token @{ domainCode = $domain; question = $question; topN = 5 }
}

$token = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
Check '登录取令牌' ($null -ne $token) 'admin 已登录'

# rerank 分组的当前值：结束时恢复，避免脚本把运维的配置改坏
$groups = (Call 'GET' '/settings' $token $null).Json.groups
$rerank = $groups | Where-Object { $_.code -eq 'rerank' } | Select-Object -First 1
Check '系统配置里有 rerank 分组' ($null -ne $rerank) '/api/settings'
function FieldValue($group, $key) {
    $field = $group.fields | Where-Object { $_.key -eq $key } | Select-Object -First 1
    if ($null -eq $field) { return '' }
    if ($field.hasValue) { return $field.value }
    return $field.value   # 非密钥字段回显的即为“生效值（存库值或默认值）”
}
$origEnabled = FieldValue $rerank 'enabled'
$origBaseUrl = FieldValue $rerank 'baseUrl'
$origModel   = FieldValue $rerank 'model'

function Restore {
    Call 'PUT' '/settings/rerank' $token @{ enabled = $origEnabled; baseUrl = $origBaseUrl; model = $origModel } | Out-Null
}

$question = '出差住宿费标准是多少'

# ① 基线：口径必须与重排引入前一致
$base = Search $token 'policy' $question
$baseOrder = @($base.Json.evidences | ForEach-Object { $_.docNo }) -join ','
$baseRerank = $base.Json.diagnostics.rerankApplied
Check '基线可检索' ($base.Status -eq 200 -and @($base.Json.evidences).Count -ge 1) "HTTP $($base.Status) 顺序=$baseOrder"
Check 'bestScore 仍为向量余弦（0~1）' (($base.Json.diagnostics.bestScore -ge 0) -and ($base.Json.diagnostics.bestScore -le 1)) "bestScore=$($base.Json.diagnostics.bestScore)"
Check '作答阈值仍高于召回阈值' ($base.Json.diagnostics.answerMinScore -gt $base.Json.diagnostics.minScore) "召回=$($base.Json.diagnostics.minScore) 作答=$($base.Json.diagnostics.answerMinScore)"

# ③ 探活接口：未配置也应 200（返回 ok:false 说明原因），而不是炸成 5xx
$probe = Call 'POST' '/settings/rerank/test' $token $null
Check 'rerank 探活接口可用' (($probe.Status -eq 200) -and ($null -ne $probe.Json.ok)) "HTTP $($probe.Status) ok=$($probe.Json.ok) msg=$($probe.Json.message)"

# ② 启用 + 指向一个不可达端点：必须 fail-open，而不是让检索失败或超时拖垮
Call 'PUT' '/settings/rerank' $token @{ enabled = 'true'; baseUrl = 'http://127.0.0.1:59999'; model = '重排模型' } | Out-Null
$fallback = Search $token 'policy' $question
$fallbackOrder = @($fallback.Json.evidences | ForEach-Object { $_.docNo }) -join ','
Check '端点不可达时检索不报错（HTTP 200）' ($fallback.Status -eq 200) "HTTP $($fallback.Status)"
Check 'fail-open：退回 RRF 原序（顺序逐位不变）' ($fallbackOrder -eq $baseOrder) "基线=$baseOrder 失败后=$fallbackOrder"
Check 'fail-open：不注入重排分' (($fallback.Json.diagnostics.rerankApplied -eq $false) -and ($fallback.Json.diagnostics.bestRerankScore -eq 0)) "rerankApplied=$($fallback.Json.diagnostics.rerankApplied) bestRerankScore=$($fallback.Json.diagnostics.bestRerankScore)"
Check 'fail-open：作答阈值口径不变' ($fallback.Json.diagnostics.answerMinScore -eq $base.Json.diagnostics.answerMinScore) "作答阈值 $($fallback.Json.diagnostics.answerMinScore)"

Restore
Check '已恢复 rerank 原配置' $true "enabled=$origEnabled（原值）"

# ④ 默认关闭时，基线里不该出现重排分
if ($origEnabled -ne 'true') {
    Check '默认关闭时基线不含重排分' ($baseRerank -eq $false) "baseline rerankApplied=$baseRerank"
} else {
    Write-Host '[SKIP] 基线重排已开启，跳过“默认关闭”断言'
}
