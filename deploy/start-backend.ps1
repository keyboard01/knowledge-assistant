# 启动后端服务。密码等敏感配置从 deploy\.env 注入，不写进代码与命令行。
param(
    [switch]$SkipEnvFile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'
$jar = Join-Path $root 'backend\target\knowledge-assistant-0.1.0-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $jar)) {
    throw "未找到构建产物：$jar`n请先执行：cd backend; mvn -o -B package"
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
java '-Dfile.encoding=UTF-8' -jar $jar
