# 启动后端服务。密码等敏感配置从 deploy\.env 注入，不写进代码与命令行。
param(
    [switch]$SkipEnvFile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'
$jar = Join-Path $root 'backend\target\knowledge-assistant-0.1.0-SNAPSHOT.jar'
$logs = Join-Path $root 'backend\logs'
$pidFile = Join-Path $logs 'app.pid'

# 日志统一按 UTF-8 落盘：Windows 上 pwsh 的输出编码默认跟随控制台代码页（GBK），
# 而 JVM 侧是 -Dfile.encoding=UTF-8，两边写进同一个 app.log 会一半乱码。
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if (-not (Test-Path -LiteralPath $logs)) { New-Item -ItemType Directory -Path $logs | Out-Null }

if (-not (Test-Path -LiteralPath $jar)) {
    throw "未找到构建产物：$jar`n请先执行：cd backend; mvn -o -B package"
}

# 只判断"文件在不在"不够：被中断的 mvn package 会留下没有 Main-Class 的瘦 jar，
# java -jar 会立刻以"没有主清单属性"退出，而 restart-backend.ps1 的探活要空转满
# 60 秒才报错——表现就是"启动后端…卡住"。这里提前失败，把真正原因摆到明面上。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$manifestText = ''
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' } | Select-Object -First 1
    if ($entry) { $manifestText = (New-Object System.IO.StreamReader($entry.Open())).ReadToEnd() }
} finally {
    $zip.Dispose()
}
if ($manifestText -notmatch '(?m)^Main-Class:') {
    throw "不是可执行的 Spring Boot 包（MANIFEST 里没有 Main-Class）：$jar`n上次构建多半被中断了。重新构建：cd backend; mvn -o -B package"
}

if (-not $SkipEnvFile) {
    if (-not (Test-Path -LiteralPath $envFile)) {
        throw "缺少配置文件：$envFile`n请参照 deploy\.env.example 创建。"
    }
    Get-Content -LiteralPath $envFile -Encoding UTF8 |
        Where-Object { $_ -match '^\s*[^#\s][^=]*=' } |
        ForEach-Object {
            $pair = $_ -split '=', 2
            $key = $pair[0].Trim()
            $value = $pair[1].Trim()
            if ($key) { Set-Item -Path "Env:$key" -Value $value }
        }
}

if (-not $env:PG_PASSWORD) {
    throw "未取到 PG_PASSWORD，请检查 deploy\.env"
}

Write-Host "启动后端：$jar" -ForegroundColor Cyan
Write-Host "数据库密码来源：deploy\.env" -ForegroundColor DarkGray
# Windows 下 JVM 默认字符集是 GBK，日志与 SQL 脚本里的中文都会变乱码
# 这个参数必须带引号：PowerShell 7 会把裸写的 -Dfile.encoding=UTF-8 拆成两个参数，JVM 直接找不到主类
# 用 Start-Process 而不是直接调用 java：这样能拿到真正的 JVM pid 写进 app.pid。
# 旧实现靠"全局最新的 java 进程"猜 pid，机器上只要还有别的 JVM（本机就常年有好几个），
# 一键停止就会杀错进程，真正的后端反而留在 9090 上继续跑。
$proc = Start-Process -FilePath 'java' `
    -ArgumentList '-Dfile.encoding=UTF-8', '-jar', $jar `
    -NoNewWindow -PassThru
Set-Content -LiteralPath $pidFile -Value $proc.Id -Encoding ascii

try {
    $proc.WaitForExit()
} finally {
    # 正常退出或被 stop-all 杀掉后清掉 pid 文件，避免下次被当成"还活着的后端"
    $recorded = Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue
    if ($recorded -and $recorded.Trim() -eq [string]$proc.Id) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }
}
exit $proc.ExitCode
