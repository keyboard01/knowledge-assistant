# 一键启动全栈：Java 17 → 数据库容器 → 内核容器 → 后端 → 前端 → 浏览器。
#
# 用法：双击根目录「启动.bat」，或手动：
#   pwsh deploy\start-all.ps1 [-Rebuild] [-SkipFrontend] [-NoBrowser]
#
# 前提：
#   - Docker Desktop 在跑；
#   - deploy\.env 已按 deploy\.env.example 填好；
#   - 内核所需的模型配置已在「系统配置」页填过一次（start-kernel 从数据库读配置）。
#
# 说明：内核容器一旦在运行就不再重建（start-kernel.ps1 会 docker rm -f 重建，代价大），
#       只在它没跑时才调用它。
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

# ---------- 3. 内核容器 ----------
if (ContainerUp 'assistant-anythingllm') {
    Info '内核容器 assistant-anythingllm：已在运行'
} else {
    Warn '内核容器未运行，调用 deploy\start-kernel.ps1 启动（会重建容器，从数据库读模型配置）…'
    & (Join-Path $PSScriptRoot 'start-kernel.ps1')
}

# ---------- 4. 后端 jar ----------
$jar = Join-Path $root 'backend\target\knowledge-assistant-0.1.0-SNAPSHOT.jar'
if ($Rebuild -or -not (Test-Path -LiteralPath $jar)) {
    Info '构建后端（mvn -o -B package）…'
    Push-Location (Join-Path $root 'backend')
    try { & mvn -o -B package } finally { Pop-Location }
}

# ---------- 5. 后端 ----------
Info '启动后端…'
& (Join-Path $PSScriptRoot 'restart-backend.ps1')

# ---------- 6. 前端 ----------
if (-not $SkipFrontend) {
    Info '启动前端（新窗口 npm run dev）…'
    $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if (-not $pwsh) { $pwsh = 'powershell' }
    Start-Process -FilePath $pwsh `
        -ArgumentList '-NoLogo', '-NoProfile', '-NoExit', '-Command', 'npm run dev' `
        -WorkingDirectory (Join-Path $root 'frontend')
}

# ---------- 7. 浏览器 ----------
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173' }

Info '完成。前端 http://127.0.0.1:5173 ；登录口令见 deploy\.env（admin / demo）。'
