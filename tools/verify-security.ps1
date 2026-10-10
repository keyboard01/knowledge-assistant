# T12 安全核查（AC-P1-012 / AC-P1-013）：可重复执行，不打印任何密钥明文。
#
# 三件事分开判，因为它们的严重程度不一样：
#   1) 明文密钥出现在前端构建产物或任何 GET 响应里  —— 这是"凭据泄露"，一律失败。
#   2) 密钥落进服务端日志                            —— 同样失败（日志会被人打包发来发去）。
#   3) workspace slug 出现在 GET 响应里              —— 只**登记**、不算失败，理由见下。
#
# 为什么 slug 不算失败：AC-P1-012 的期望列写的是"不出现明文密钥"，slug 不是凭据——
# 没有 API Key 时它只是一串名字，而"域 → 工作区"的对照关系正是运维页要给人看的东西。
# 但"检查项"里确实点了 slug，所以这里把它逐条列出来交给你拍板：要么接受，要么把
# 运维页也改成不显示 slug（改 KernelView + selfcheck 的返回体）。
#
# 本脚本会短暂地把 LLM 的 API Key 改成一个金丝雀值再改回来（验"保存配置的 PUT 不写日志"），
# 所以**必须与其他验收脚本串行执行**，不能和需要调生成模型的脚本并行跑。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root 'deploy\.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw "找不到 $envPath" }
$envFile = Get-Content -LiteralPath $envPath -Encoding UTF8
function EnvValue($key) {
    ($envFile | Where-Object { $_ -match "^\s*$key=" } | Select-Object -First 1) -replace "^\s*$key=", ''
}
function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri = "http://127.0.0.1:9090/api$path"; Method = $method; Headers = $headers
        SkipHttpErrorCheck = $true; TimeoutSec = 120
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 6); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    return @{ Status = [int]$response.StatusCode; Raw = $response.Content }
}
function Check($name, $ok, $detail) {
    Write-Host ("{0} {1} —— {2}" -f ($(if ($ok) { '[OK]  ' } else { '[FAIL]' })), $name, $detail)
}

# 指纹只用来"证明读到的是同一串"，不用来还原：打印前 3 + 后 2 位会泄漏，哈希不会。
function Fingerprint($value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($value))
    return 'sha256:' + ([BitConverter]::ToString($bytes) -replace '-', '').Substring(0, 12).ToLower()
}

# ---------- 收集"必须不出现"的密钥池 ----------
# 库里的密钥字段（secret = true）+ deploy\.env 里的口令：两者都是凭据，
# 前者可能从接口漏，后者可能从构建产物漏。
$pgRows = (docker exec assistant-pg psql -U assistant -d assistant -tAc `
        "select group_code || '.' || field_key || '|' || field_value from app_setting where secret and field_value <> ''" 2>&1) -join "`n"
$secrets = [ordered]@{}
foreach ($line in ($pgRows -split "`n")) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or -not $trimmed.Contains('|')) { continue }
    $parts = $trimmed.Split('|', 2)
    $secrets[$parts[0]] = $parts[1]
}
foreach ($key in @('ASSISTANT_ADMIN_PASSWORD', 'ASSISTANT_DEMO_PASSWORD', 'POSTGRES_PASSWORD')) {
    $value = EnvValue $key
    if ($value) { $secrets["env.$key"] = $value }
}
Write-Host "密钥池：$($secrets.Count) 项（只打印指纹，明文不出现在输出里）"
foreach ($key in $secrets.Keys) {
    Write-Host ("  {0}  长度={1}  {2}" -f $key, $secrets[$key].Length, (Fingerprint $secrets[$key]))
}
if ($secrets.Count -eq 0) { throw '一个密钥都没读到，后面的检查没有意义' }

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Raw | ConvertFrom-Json
$adminToken = $adminToken.token
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Raw | ConvertFrom-Json
$demoToken = $demoToken.token
if (-not $adminToken -or -not $demoToken) { throw '登录失败，无法做安全核查' }

function Find-Secrets($text) {
    $hits = @()
    foreach ($key in $secrets.Keys) {
        if ($text.Contains($secrets[$key])) { $hits += $key }
    }
    return $hits
}

# ---------- 1. 前端构建产物 ----------
Write-Host "`n---------- ① 前端构建产物（AC-P1-012） ----------"
$dist = Join-Path $root 'frontend\dist'
if (-not (Test-Path -LiteralPath $dist)) {
    Check '前端产物存在（先跑 cd frontend; npm run build）' $false "找不到 $dist"
} else {
    $files = Get-ChildItem -LiteralPath $dist -Recurse -File
    $blob = ($files | ForEach-Object { [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8) }) -join "`n"
    $hits = Find-Secrets $blob
    Check '构建产物里没有明文密钥' ($hits.Count -eq 0) `
        $(if ($hits.Count -eq 0) { "已扫描 $($files.Count) 个文件，$($blob.Length) 字符，命中 0" } else { "命中：$($hits -join ', ')" })

    $domains = (Call 'GET' '/domains' $adminToken $null).Raw | ConvertFrom-Json
    $slugs = @($domains.domains | ForEach-Object { $_.workspaceSlug })
    $slugHits = @($slugs | Where-Object { $_ -and $blob.Contains($_) })
    Check '构建产物里没有内核工作区 slug' ($slugHits.Count -eq 0) `
        $(if ($slugHits.Count -eq 0) { "已核对 $($slugs.Count) 个 slug" } else { "命中：$($slugHits -join ', ')" })
}

# ---------- 2. 所有 GET 响应 ----------
Write-Host "`n---------- ② 全部 GET 响应（AC-P1-012） ----------"
$traceId = @((Call 'GET' '/observe/traces?limit=1' $adminToken $null).Raw | ConvertFrom-Json).traces
$traceId = if ($traceId) { $traceId[0].id } else { 1 }
# 新增 GET 接口时在这里登记：不做自动枚举，是因为"漏登记的接口"必须能被一眼看出来
$gets = @(
    @{ path = '/domains'; role = 'admin' }, @{ path = '/domains'; role = 'demo' },
    @{ path = '/domains/selfcheck'; role = 'admin' },
    @{ path = '/documents'; role = 'admin' }, @{ path = '/documents'; role = 'demo' },
    @{ path = '/settings'; role = 'admin' },
    @{ path = '/ingest/tasks'; role = 'admin' },
    @{ path = '/observe/traces?limit=5'; role = 'admin' },
    @{ path = "/observe/traces/$traceId"; role = 'admin' },
    @{ path = '/observe/summary'; role = 'admin' },
    @{ path = '/observe/price'; role = 'admin' }
)
$leaks = @()
$slugSightings = @()
foreach ($item in $gets) {
    $token = if ($item.role -eq 'admin') { $adminToken } else { $demoToken }
    $response = Call 'GET' $item.path $token $null
    if ($response.Status -ne 200) { $leaks += "$($item.path)[$($item.role)] HTTP $($response.Status)" ; continue }
    $found = Find-Secrets $response.Raw
    foreach ($key in $found) { $leaks += "$($item.path)[$($item.role)] 命中 $key" }
    if ($slugs) {
        foreach ($slug in $slugs) {
            if ($slug -and $response.Raw.Contains($slug)) { $slugSightings += "$($item.path)[$($item.role)] → $slug" }
        }
    }
}
Check '全部 GET 响应里没有明文密钥' ($leaks.Count -eq 0) `
    $(if ($leaks.Count -eq 0) { "已核对 $($gets.Count) 个请求（admin 与 demo 两种身份）" } else { $leaks -join '；' })

# slug 只登记不判失败：它不是凭据，且运维页靠它把"域"和"内核工作区"对上号。
if ($slugSightings.Count -eq 0) {
    Write-Host '[OK]   内核工作区 slug 也没有出现在 GET 响应里'
} else {
    Write-Host ("[NOTE] 内核工作区 slug 出现在 {0} 处：{1}" -f $slugSightings.Count, ($slugSightings -join '；'))
    Write-Host '       它单独不是凭据（没有 API Key 只是一串名字），但 AC 的检查项点了它——要彻底不出现，得让 /api/domains 与 selfcheck 不再回 slug，代价是运维页看不到域与工作区的对应关系。'
}

# ---------- 3. AC-P1-013：接口只回掩码 ----------
Write-Host "`n---------- ③ 密钥只回掩码（AC-P1-013） ----------"
$settings = ((Call 'GET' '/settings' $adminToken $null).Raw | ConvertFrom-Json).groups
$badValue = @(); $badMask = @(); $secretFields = @()
foreach ($group in $settings) {
    foreach ($field in $group.fields) {
        if (-not $field.secret) { continue }
        $secretFields += "$($group.code).$($field.key)"
        if ($field.value -ne '') { $badValue += "$($group.code).$($field.key)='$($field.value)'" }
        if ($field.hasValue) {
            $plain = $secrets["$($group.code).$($field.key)"]
            if (-not $plain) { continue }
            if ($field.mask -eq $plain -or $field.mask.Contains($plain)) { $badMask += "$($group.code).$($field.key)" }
        }
    }
}
Check 'GET /api/settings 的密钥字段 value 恒为空串' ($badValue.Count -eq 0) `
    $(if ($badValue.Count -eq 0) { "$($secretFields.Count) 个密钥字段全部为空串" } else { $badValue -join '；' })
Check '密钥掩码不等于明文（也没把明文包含进去）' ($badMask.Count -eq 0) `
    $(if ($badMask.Count -eq 0) { '掩码形如 sk-****1234，与明文不可互推' } else { "异常字段：$($badMask -join ', ')" })
Check '对照组：库里确实存着明文（否则"没泄露"只是因为"没配置"）' `
    (@($secrets.Keys | Where-Object { $_ -like 'llm.*' -or $_ -like 'anythingllm.*' }).Count -gt 0) `
    "库里非空密钥字段：$(@($secrets.Keys | Where-Object { $_ -notlike 'env.*' }) -join ', ')"

# ---------- 4. 保存配置的 PUT：允许带密钥，但不得写日志 ----------
Write-Host "`n---------- ④ 保存配置的 PUT 不写日志（AC-P1-012） ----------"
$canary = 'sk-canary-' + ([guid]::NewGuid().ToString('N').Substring(0, 12))
$original = $secrets['llm.apiKey']
$logs = Get-ChildItem -LiteralPath (Join-Path $root 'backend\logs') -Filter *.log -File -ErrorAction SilentlyContinue
try {
    $saved = Call 'PUT' '/settings/llm' $adminToken @{ values = @{ apiKey = $canary } }
    Check 'PUT 保存密钥成功（允许携带密钥）' ($saved.Status -eq 200) "HTTP $($saved.Status)"
    Check '保存后的响应体不回显明文（只回掩码）' (-not $saved.Raw.Contains($canary)) `
        $(if ($saved.Raw.Contains($canary)) { '响应体里出现了刚保存的明文' } else { '响应体里没有刚保存的明文' })

    # 正在跑的后端把 app.log / app.err.log 一直开着写，独占读会失败。
    # 失败就"读不到 → 当作没命中"是**假通过**，所以：共享读 + 读不到直接算失败。
    $logHits = @()
    $logTexts = @{}
    foreach ($log in $logs) {
        try {
            $stream = [System.IO.File]::Open($log.FullName,
                [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
            try {
                $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
                $logTexts[$log.Name] = $reader.ReadToEnd()
                $reader.Dispose()
            } finally { $stream.Dispose() }
        } catch {
            $logHits += "$($log.Name)（读不到：$($_.Exception.Message)）"
        }
    }
    # 对照组：这次脚本自己刚登录过，日志里必须有那一行——否则"命中 0"可能只是读到个空文件
    $live = @($logTexts.Keys | Where-Object { $logTexts[$_].Contains('登录成功 username=admin') })
    Check '对照组：读到的是正在写的日志（本次登录能在里面找到）' ($live.Count -gt 0) `
        $(if ($live.Count -gt 0) { "$($live -join ', ')（共读 $($logTexts.Count) 个文件）" } else { "本次登录没出现在任何日志里，读到的文件可能是旧的或空的：$($logTexts.Keys -join ', ')" })

    foreach ($name in $logTexts.Keys) {
        if ($logTexts[$name].Contains($canary)) { $logHits += "$name（命中金丝雀）" }
    }
    Check '服务端日志里没有密钥明文（用金丝雀值试写一次）' ($logHits.Count -eq 0) `
        $(if ($logHits.Count -eq 0) { "已扫描 $($logTexts.Count) 个日志文件，命中 0" } else { "命中：$($logHits -join ', ')" })
} finally {
    $restored = Call 'PUT' '/settings/llm' $adminToken @{ values = @{ apiKey = $original } }
    $after = ((Call 'GET' '/settings' $adminToken $null).Raw | ConvertFrom-Json).groups |
        Where-Object { $_.code -eq 'llm' } | ForEach-Object { $_.fields } | Where-Object { $_.key -eq 'apiKey' }
    Check '金丝雀已还原成原密钥' ($restored.Status -eq 200 -and $after.hasValue) `
        "HTTP $($restored.Status)，hasValue=$($after.hasValue)，mask=$($after.mask)"
}

Write-Host "`n安全核查完成：明文密钥与日志两项是硬判据；slug 一项已如实登记（见上方 NOTE）。"