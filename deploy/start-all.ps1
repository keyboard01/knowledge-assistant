# 一键启动全栈：Java 17 → 数据库容器 → 后端 → 前端 → 浏览器。
#
# 用法：双击根目录「启动.bat」，或手动：
#   pwsh deploy\start-all.ps1 [-Rebuild] [-SkipFrontend] [-NoBrowser]
#
# 前提：
#   - Docker Desktop 在跑；
#   - deploy\.env 已按 deploy\.env.example 填好。
#
# 说明：数据库容器只在缺失时创建、已存在时直接启动，不会删除重建（数据都在命名卷里）。
param(
    [switch]$Rebuild,       # 强制重新打包后端 jar
    [switch]$SkipFrontend,  # 只起后端，不起前端
    [switch]$NoBrowser      # 不自动打开浏览器
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Info($m) { Write-Host $m -ForegroundColor Cyan }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }

# ---------- 1. Java 17 ----------
# 参数名不能叫 $home：PowerShell 的 $HOME 是只读自动变量，会报 "Cannot overwrite variable home"。
function Test-Java17([string]$javaDir) {
    if (-not $javaDir) { return $false }
    $exe = Join-Path $javaDir 'bin\java.exe'
    if (-not (Test-Path $exe)) { return $false }
    $line = [string](& $exe -version 2>&1 | Select-Object -First 1)
    return ($line -match 'version "17')
}

function Resolve-JavaHome {
    if (Test-Java17 $env:JAVA_HOME) { return $env:JAVA_HOME }
    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add('D:\jdk17\jdk-17.0.7_windows-x64_bin\jdk-17.0.7')
    foreach ($base in 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft') {
        if (Test-Path -LiteralPath $base) {
            Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match 'jdk-?17' } |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    foreach ($candidate in $candidates) { if (Test-Java17 $candidate) { return $candidate } }
    throw "找不到 Java 17。请安装 JDK 17，或先设置 `$env:JAVA_HOME 指向 JDK17 后重试。当前 JAVA_HOME=$env:JAVA_HOME"
}

$javaHome = Resolve-JavaHome
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Info "Java 17：$javaHome"

# ---------- 0. docker 可用性 ----------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '找不到 docker 命令：请先启动 Docker Desktop，或确认 docker 在 PATH 上。'
}
function ContainerUp($name) {
    return [bool](docker ps --format '{{.Names}}' 2>$null | Where-Object { $_ -eq $name })
}
function ContainerExists($name) {
    docker inspect $name *> $null
    return ($LASTEXITCODE -eq 0)
}

# ---------- 2. 数据库容器 ----------
$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw '缺少 deploy\.env（参照 deploy\.env.example 创建）' }
$pgLine = Get-Content -LiteralPath $envFile -Encoding UTF8 |
    Where-Object { $_ -match '^\s*PG_PASSWORD=' } | Select-Object -First 1
$pgPassword = if ($pgLine) { ($pgLine -split '=', 2)[1].Trim() } else { '' }
if (-not $pgPassword) { throw '未取到 PG_PASSWORD，请检查 deploy\.env' }

if (ContainerUp 'assistant-pg') {
    Info '数据库容器 assistant-pg：已在运行'
} elseif (ContainerExists 'assistant-pg') {
    Info '启动已存在的数据库容器 assistant-pg…'
    docker start assistant-pg | Out-Null
    Start-Sleep -Seconds 2
} else {
    Info '创建并启动数据库容器 assistant-pg…'
    docker run -d --name assistant-pg --restart unless-stopped `
        -p 127.0.0.1:5432:5432 `
        -e POSTGRES_USER=assistant `
        -e "POSTGRES_PASSWORD=$pgPassword" `
        -e POSTGRES_DB=assistant `
        -v assistant_pg_data:/var/lib/postgresql/data `
        pgvector/pgvector:pg17 | Out-Null
    Start-Sleep -Seconds 3
}

# ---------- 3. 后端 jar ----------
$jar = Join-Path $root 'backend\target\knowledge-assistant-0.1.0-SNAPSHOT.jar'

# 只判断 jar 在不在不够：上次 mvn package 被中断会留下没有 Main-Class 的瘦 jar，
# 它能通过 Test-Path，却让 java -jar 立刻失败、restart-backend 的探活空转 60 秒（看着像卡死）。
function Test-BootJar([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' } | Select-Object -First 1
        if (-not $entry) { return $false }
        $text = (New-Object System.IO.StreamReader($entry.Open())).ReadToEnd()
        return ($text -match '(?m)^Main-Class:')
    } finally {
        $zip.Dispose()
    }
}

if ($Rebuild -or -not (Test-BootJar $jar)) {
    Info '构建后端（mvn -o -B package）…'
    Push-Location (Join-Path $root 'backend')
    try { & mvn -o -B package } finally { Pop-Location }
}

# ---------- 4. 后端 ----------
Info '启动后端…'
& (Join-Path $PSScriptRoot 'restart-backend.ps1')
if ($LASTEXITCODE -ne 0) {
    Warn "后端未能正常启动（exit=$LASTEXITCODE）。已跳过前端与浏览器，先按上面的提示处理。"
    exit $LASTEXITCODE
}

# ---------- 5. 前端 ----------
# Vite 被占用时会自动改用 5174，所以按"哪个端口有人在听"判断前端是不是已经在跑，
# 免得一键启动点两次就多起一个 dev server。
# Get-NetTCPConnection 在受限终端里可能整个返回空，这里同样退回 netstat。
function Get-ListeningPid([int]$port) {
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($connections) {
        return @($connections | ForEach-Object { $_.OwningProcess } | Where-Object { $_ -gt 0 } | Select-Object -Unique)
    }
    return @(
        netstat -ano |
            Select-String ":$port\s+[^\s]+\s+LISTENING\s+(\d+)" |
            ForEach-Object { [int]$_.Matches[0].Groups[1].Value } |
            Where-Object { $_ -gt 0 } |
            Select-Object -Unique
    )
}

function Get-FrontendUrl {
    foreach ($candidate in 5173, 5174) {
        if (Get-ListeningPid $candidate) {
            return "http://127.0.0.1:$candidate"
        }
    }
    return $null
}

function Get-NodeVersion {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { return $null }
    $raw = & node --version 2>$null
    if (-not $raw) { return $null }
    $parsed = $null
    if (-not [version]::TryParse(([string]$raw).Trim().TrimStart('v'), [ref]$parsed)) { return $null }
    return $parsed
}

function Test-Node18 {
    $v = Get-NodeVersion
    return [bool]($v -and $v -ge [version]'18.0.0')
}

function Get-NodeVersionLabel {
    $v = Get-NodeVersion
    if ($v) { return "v$v" }
    return '未安装 node'
}

$frontendUrl = Get-FrontendUrl
if ($SkipFrontend) {
    Info '按 -SkipFrontend 跳过前端。'
    $frontendUrl = $null
} elseif ($frontendUrl) {
    Info "前端已在运行：$frontendUrl"
} elseif (-not (Test-Node18)) {
    # 前端要 Node 18+（docs\交付部署.md 的环境要求）。本机 nvm 停在旧版本时，
    # vite 会崩在它自己那个窗口里（典型报错 Unexpected token '??='），这边只看得到"没监听"。
    Warn "前端需要 Node 18+，当前是 $(Get-NodeVersionLabel)。先切换（例如 nvm use 22.22.0）再点一次；只想起后端就加 -SkipFrontend。"
} else {
    Info '启动前端（新窗口 npm run dev）…'
    $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if (-not $pwsh) { $pwsh = 'powershell' }
    Start-Process -FilePath $pwsh `
        -ArgumentList '-NoLogo', '-NoProfile', '-NoExit', '-Command', 'npm run dev' `
        -WorkingDirectory (Join-Path $root 'frontend')
    for ($i = 0; $i -lt 40 -and -not $frontendUrl; $i++) {
        Start-Sleep -Milliseconds 500
        $frontendUrl = Get-FrontendUrl
    }
    if ($frontendUrl) { Info "前端已就绪：$frontendUrl" }
    else { Warn '前端 20 秒内没有监听 5173/5174，请看它自己那个窗口的输出。' }
}

# ---------- 6. 浏览器 ----------
if ($NoBrowser) {
    Info '按 -NoBrowser 跳过打开浏览器。'
} elseif ($frontendUrl) {
    Start-Process $frontendUrl
} else {
    Warn '前端未就绪，跳过打开浏览器。'
}

if ($frontendUrl) {
    Info "完成。用户门户 $frontendUrl ；管理控制台 $frontendUrl/admin.html ；登录口令见 deploy\.env（admin / demo）。"
} else {
    Info '后端已就绪：http://127.0.0.1:9090 ；登录口令见 deploy\.env（admin / demo）。'
}

# 要起前端却没起来 = 一键启动没完成，不能让窗口默默关掉、假装成功
if (-not $SkipFrontend -and -not $frontendUrl) {
    exit 1
}
exit 0
