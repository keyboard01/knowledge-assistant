# T06 登录与权限的验收脚本：可重复执行，只读 deploy\.env 取口令，不打印口令。
# 覆盖：未登录 401、密码错误 401、管理员/viewer 的可见差异、角色 403、自助改密、注销即失效、库中哈希形态。
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
$adminPw = EnvValue 'ASSISTANT_ADMIN_PASSWORD'
$demoPw = EnvValue 'ASSISTANT_DEMO_PASSWORD'

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri = "http://127.0.0.1:9090/api$path"; Method = $method; Headers = $headers
        SkipHttpErrorCheck = $true; TimeoutSec = 60
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

# 1. 未登录：默认拒绝
$anon = Call 'GET' '/documents' $null
Check '未登录访问文档列表' ($anon.Status -eq 401) "HTTP $($anon.Status)：$($anon.Json.error)"

# 2. 密码错误
$bad = Call 'POST' '/auth/login' $null @{ username = 'admin'; password = 'definitely-wrong' }
Check '密码错误被拒' ($bad.Status -eq 401) "HTTP $($bad.Status)：$($bad.Json.error)"

# 3. 老接口没有令牌也要挡住
$anonScan = Call 'POST' '/ingest/scan' $null
Check '未登录触发同步' ($anonScan.Status -eq 401) "HTTP $($anonScan.Status)"

# 4. 管理员登录
$admin = Call 'POST' '/auth/login' $null @{ username = 'admin'; password = $adminPw }
$adminToken = $admin.Json.token
Check '管理员登录' ($admin.Status -eq 200 -and $adminToken) "HTTP $($admin.Status)，角色 $($admin.Json.user.roleCode)，有效期 $($admin.Json.expiresAt)"
Check '令牌是随机串而非明文口令' ($adminToken -ne $adminPw -and $adminToken.Length -eq 43) "长度 $($adminToken.Length)"

# 5. 管理员可见全部文档
$adminDocs = Call 'GET' '/documents' $adminToken
$adminList = $adminDocs.Json.documents
Check '管理员可见文档数' ($adminList.Count -eq 8) "$($adminList.Count) 份"
$adminUsers = Call 'GET' '/auth/users' $adminToken
Check '管理员可列账号' ($adminUsers.Status -eq 200 -and $adminUsers.Json.users.Count -ge 2) "$($adminUsers.Json.users.Count) 个账号"
Check '账号列表不含口令哈希' ($adminUsers.Raw -notmatch '\$2a\$') '响应里没有 $2a$ 开头的哈希'

# 6. 演示账号（viewer）：看不到秘密级文档
$demo = Call 'POST' '/auth/login' $null @{ username = 'demo'; password = $demoPw }
$demoToken = $demo.Json.token
Check '演示账号登录' ($demo.Status -eq 200 -and $demoToken) "HTTP $($demo.Status)，角色 $($demo.Json.user.roleCode)"
$demoDocs = Call 'GET' '/documents' $demoToken
$demoList = $demoDocs.Json.documents
Check '演示账号可见文档数' ($demoList.Count -eq 7) "$($demoList.Count) 份（少 1 份秘密级）"
Check '秘密级文档未泄露（标题与内容）' (($demoDocs.Raw -notmatch '薪酬') -and ($demoList | Where-Object { $_.secrecy -eq '秘密' }).Count -eq 0) (($demoList | Where-Object { $_.secrecy -eq '秘密' }).Count -eq 0)

# 7. 角色门槛：viewer 不能触发同步、不能列账号
$demoScan = Call 'POST' '/ingest/scan' $demoToken
Check 'viewer 触发同步被拒' ($demoScan.Status -eq 403) "HTTP $($demoScan.Status)：$($demoScan.Json.error)"
$demoUsers = Call 'GET' '/auth/users' $demoToken
Check 'viewer 列账号被拒' ($demoUsers.Status -eq 403) "HTTP $($demoUsers.Status)"

# 8. 自助改密：旧密码错 400；正确则成功且不影响本次会话
$wrongOld = Call 'POST' '/auth/password' $demoToken @{ oldPassword = 'nope'; newPassword = 'Whatever#2026' }
Check '旧密码错误返回 400' ($wrongOld.Status -eq 400) "HTTP $($wrongOld.Status)：$($wrongOld.Json.error)"
$tooShort = Call 'POST' '/auth/password' $demoToken @{ oldPassword = $demoPw; newPassword = 'short' }
Check '新密码过短被拒' ($tooShort.Status -eq 400) "HTTP $($tooShort.Status)：$($tooShort.Json.error)"
$changed = Call 'POST' '/auth/password' $demoToken @{ oldPassword = $demoPw; newPassword = $demoPw }
Check '改密成功（回落为原值，状态不变）' ($changed.Status -eq 200) "HTTP $($changed.Status)"
$stillValid = Call 'GET' '/documents' $demoToken
Check '改密后当前会话仍有效' ($stillValid.Status -eq 200) "HTTP $($stillValid.Status)"
$relogin = Call 'POST' '/auth/login' $null @{ username = 'demo'; password = $demoPw }
Check '改密后新登录正常' ($relogin.Status -eq 200) "HTTP $($relogin.Status)"

# 9. 注销即时生效
$logout = Call 'POST' '/auth/logout' $adminToken
$afterLogout = Call 'GET' '/documents' $adminToken
Check '注销后旧令牌立即失效' ($logout.Status -eq 200 -and $afterLogout.Status -eq 401) "注销 HTTP $($logout.Status)，复用旧令牌 HTTP $($afterLogout.Status)"

# 10. 库里的口令与令牌形态
$sql = "select count(*), count(*) filter (where password_hash like '`$2a`$%') from app_user"
$pg = (docker exec assistant-pg psql -U assistant -d assistant -tAc $sql 2>&1) -join ''
Check 'app_user 口令全为 BCrypt 哈希' ($pg -match '^2\|2') "count|bcrypt = $pg"
$sess = (docker exec assistant-pg psql -U assistant -d assistant -tAc 'select count(*) filter (where length(token_hash)=64) || ''/'' || count(*) from app_session' 2>&1) -join ''
Check 'app_session 只存 64 位摘要' ($sess -match '^(\d+)/\1$') "hashed/total = $sess"
