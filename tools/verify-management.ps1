# 管理能力（P1）验收：知识域 CRUD、文档版本状态切换、admin-only 权限、删除守卫。
# 可重复执行；**不落持久数据**：知识域建了会删掉，文档状态改动会还原。
# 为什么不做"真实文件上传"：系统里没有删除文档的接口，上传会留下无法清理的痕迹；
# 上传的端到端验证请在界面里手工做一次（上传 → 文档列表出现 → 解析预览有块）。
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
        SkipHttpErrorCheck = $true; TimeoutSec = 90
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++ } else { $script:fail++ }
    $flag = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("{0} {1} —— {2}" -f $flag, $name, $detail)
}

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
Check '登录取令牌' ($adminToken -and $demoToken) 'admin 与 demo 均已登录'

# ---------- 1. admin-only ----------
Write-Host "`n---------- 权限：管理动作限管理员 ----------"
Check '非管理员不能新建知识域' ((Call 'POST' '/domains' $demoToken @{ code = 'qa-x'; name = 'x' }).Status -eq 403) 'demo → 403'
Check '非管理员不能删除知识域' ((Call 'DELETE' '/domains/tech' $demoToken $null).Status -eq 403) 'demo → 403'
$documents = @((Call 'GET' '/documents' $adminToken $null).Json.documents)
$firstDoc = $documents | Select-Object -First 1
if ($firstDoc) {
    Check '非管理员不能切换文档状态' `
        ((Call 'PUT' "/documents/$($firstDoc.id)/status" $demoToken @{ docStatus = '现行' }).Status -eq 403) 'demo → 403'
}

# ---------- 2. 校验守卫 ----------
Write-Host "`n---------- 知识域校验 ----------"
Check '非法 code 被拒' ((Call 'POST' '/domains' $adminToken @{ code = 'A B'; name = 'n' }).Status -eq 400) '400'
Check '重复 code 被拒' ((Call 'POST' '/domains' $adminToken @{ code = 'policy'; name = 'n' }).Status -eq 400) '400'

# ---------- 3. CRUD 闭环（无文档 → 可删） ----------
Write-Host "`n---------- 知识域 CRUD ----------"
$code = 'qa' + (Get-Date -Format 'HHmmss')
$created = Call 'POST' '/domains' $adminToken @{ code = $code; name = '验收域'; description = '临时' }
Check '新建知识域' ($created.Status -eq 200 -and $created.Json.code -eq $code) "HTTP $($created.Status) code=$code"
$codes = @((Call 'GET' '/domains' $adminToken $null).Json.domains | ForEach-Object { $_.code })
Check '列表包含新建的域' ($codes -contains $code) ($codes -join ',')
$updated = Call 'PUT' "/domains/$code" $adminToken @{ name = '验收域-改名'; enabled = $false }
Check '更新域（改名 + 停用）' ($updated.Status -eq 200 -and $updated.Json.enabled -eq $false `
        -and $updated.Json.name -eq '验收域-改名') "HTTP $($updated.Status) name=$($updated.Json.name) enabled=$($updated.Json.enabled)"
Check '删除无文档的知识域' ((Call 'DELETE' "/domains/$code" $adminToken $null).Status -eq 204) '204'
$codesAfter = @((Call 'GET' '/domains' $adminToken $null).Json.domains | ForEach-Object { $_.code })
Check '删除后列表不含该域' ($codesAfter -notcontains $code) ($codesAfter -join ',')
if ($firstDoc) {
    Check '有文档的知识域删除被拒' `
        ((Call 'DELETE' "/domains/$($firstDoc.domainCode)" $adminToken $null).Status -eq 400) "$($firstDoc.domainCode) 有文档 → 400"
}

# ---------- 4. 文档版本状态（改完还原） ----------
Write-Host "`n---------- 文档状态切换 ----------"
if ($firstDoc) {
    $original = $firstDoc.docStatus
    Check '非法文档状态被拒' `
        ((Call 'PUT' "/documents/$($firstDoc.id)/status" $adminToken @{ docStatus = '不存在' }).Status -eq 400) '400'
    $target = if ($original -eq '已废止') { '现行' } else { '已废止' }
    $changed = Call 'PUT' "/documents/$($firstDoc.id)/status" $adminToken @{ docStatus = $target }
    Check '切换文档状态' ($changed.Status -eq 200 -and $changed.Json.docStatus -eq $target) "→ $target"
    $restore = Call 'PUT' "/documents/$($firstDoc.id)/status" $adminToken @{ docStatus = $original }
    Check '状态已还原（可重复执行）' ($restore.Status -eq 200 -and $restore.Json.docStatus -eq $original) "→ $original"
}

# ---------- 汇总 ----------
Write-Host ""
Write-Host ("通过 {0} / {1} 项检查" -f $script:pass, ($script:pass + $script:fail))
if ($script:fail -gt 0) { exit 1 }
