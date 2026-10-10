# 启动（或重建）本项目专用的 AnythingLLM 内核容器。
#
# 为什么要有独立实例：EMBEDDING_ENGINE / EMBEDDING_MODEL_PREF 是整实例全局的，
# 和公司其它项目共用一台内核，就意味着改向量化模型会把别人的索引一起作废。
#
# 配置来源是 PostgreSQL 的 app_setting（唯一事实来源），不从文件读密钥，
# 避免密钥在两处漂移。改完前端配置页后重跑本脚本即可让内核跟上。
param(
    [string]$Name = 'assistant-anythingllm',
    [int]$Port = 3002,
    [string]$StorageDir = 'D:\DockerData\AnythingLLM-assistant',
    [string]$Image = 'mintplexlabs/anythingllm:latest',
    [string]$PgContainer = 'assistant-pg',
    [string]$PgUser = 'assistant',
    [string]$PgDb = 'assistant'
)

$ErrorActionPreference = 'Stop'

function Get-Setting([string]$group, [string]$field) {
    $sql = "select coalesce(field_value,'') from app_setting where group_code='$group' and field_key='$field'"
    $value = (docker exec $PgContainer psql -U $PgUser -d $PgDb -tAc $sql)
    if (-not $value) { throw "配置缺失：$group.$field（请先在后端配置页填写）" }
    return $value.Trim()
}

$embeddingModel = Get-Setting 'embedding' 'model'
$embeddingBase = Get-Setting 'embedding' 'baseUrl'
$embeddingKey = Get-Setting 'embedding' 'apiKey'
$llmBase = Get-Setting 'llm' 'baseUrl'
$llmModel = Get-Setting 'llm' 'model'
$llmKey = Get-Setting 'llm' 'apiKey'

if (-not $llmKey) { $llmKey = $embeddingKey }

# 内核用 OpenAI SDK，baseURL 必须带 /v1
if ($llmBase -notmatch '/v1/?$') { $llmBase = $llmBase.TrimEnd('/') + '/v1' }
$embeddingBase = $embeddingBase.TrimEnd('/')

New-Item -ItemType Directory -Force -Path $StorageDir | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $StorageDir '.env'))) {
    Set-Content -LiteralPath (Join-Path $StorageDir '.env') -Value '' -NoNewline
}

docker rm -f $Name 2>$null | Out-Null

docker run -d --name $Name --restart unless-stopped `
    -p "127.0.0.1:${Port}:3001" `
    -v "${StorageDir}:/app/server/storage" `
    -v "${StorageDir}\.env:/app/server/.env" `
    -e STORAGE_DIR=/app/server/storage `
    -e DISABLE_TELEMETRY=true `
    -e EMBEDDING_ENGINE=generic-openai `
    -e "EMBEDDING_MODEL_PREF=$embeddingModel" `
    -e "EMBEDDING_BASE_PATH=$embeddingBase" `
    -e "GENERIC_OPEN_AI_EMBEDDING_API_KEY=$embeddingKey" `
    -e GENERIC_OPEN_AI_EMBEDDING_MAX_CONCURRENT_CHUNKS=10 `
    -e LLM_PROVIDER=generic-openai `
    -e "GENERIC_OPEN_AI_BASE_PATH=$llmBase" `
    -e "GENERIC_OPEN_AI_MODEL_PREF=$llmModel" `
    -e "GENERIC_OPEN_AI_API_KEY=$llmKey" `
    -e GENERIC_OPEN_AI_MODEL_TOKEN_LIMIT=8192 `
    -e GENERIC_OPEN_AI_MAX_TOKENS=4096 `
    $Image | Out-Null

Write-Host "内核容器 $Name 已启动，地址 http://127.0.0.1:$Port" -ForegroundColor Cyan
Write-Host "向量化：$embeddingModel @ $embeddingBase；生成模型：$llmModel @ $llmBase" -ForegroundColor DarkGray
Write-Host "请稍候 10 秒后执行：Invoke-RestMethod http://127.0.0.1:9090/api/domains/selfcheck" -ForegroundColor DarkGray
