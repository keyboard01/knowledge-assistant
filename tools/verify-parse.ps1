# T14/T16 解析与页码的验收脚本（AC-P2-001 / AC-P2-002 / AC-P2-003）：可重复执行，只读 deploy\.env 取口令，不打印口令。
#
# 三条各自要回答一个问题：
#   AC-P2-001 解析全不全 —— 每份文档都有片段、正文与向量；失败要标原因，不留半成品
#   AC-P2-002 幂等       —— 内容没变时重跑，片段不增、id 不变（不重算 embedding）
#   AC-P2-003 页码对不对 —— 用 pypdf 独立复核（与后端的 PDFBox 是完全不同的实现）
#
# AC-P2-003 刻意不复用后端的解析器：让被测对象证明自己是对的，等于没测。
# 代价是两套实现的取字顺序不完全一致（表格尤其明显），所以抽查只取 PARAGRAPH / BULLET 片段，
# 并把两侧的空白全部抹掉再比——比的是"这一页上有没有这段话"，不是"排版一致不一致"。
#
# 注意：本脚本会强制重解析一次（会调用 embedding，约几秒）。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
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
        SkipHttpErrorCheck = $true; TimeoutSec = 600
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = $response.Content }
}

# 库内事实一律直连数据库取，不经过被测的接口——接口说"成功"和库里真的写对了是两件事。
function Sql([string]$query) {
    $out = & docker exec assistant-pg psql -U assistant -d assistant -t -A -F '|' -c $query 2>&1
    if ($LASTEXITCODE -ne 0) { throw "psql 查询失败：$out`nSQL: $query" }
        # 只返回一行时 PowerShell 会把数组拆成标量。调用方一律用 @() 包住，别直接下标。
    return @($out | Where-Object { $_ -ne '' })
}

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++ } else { $script:fail++ }
    $flag = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("{0} {1} —— {2}" -f $flag, $name, $detail)
}

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
Check '登录取令牌' ($null -ne $adminToken) 'admin 已登录'

# ---------- AC-P2-001：解析覆盖 ----------
Write-Host "`n---------- AC-P2-001 解析覆盖 ----------"
# A3：强制重解析不许换 id。"整份删了重灌"会让历史账本里指向旧 id 的引用全部悬空（实测 3088/3170 条），
# 所以先在重解析前取一次指纹：片段总行数、最大 id、(id, 序号, 内容哈希) 的 md5。三者全同才算 id 没变。
$chunkFingerprint = "select count(*) || '|' || coalesce(max(id), 0) || '|' || coalesce(md5(string_agg(id || ':' || chunk_index || ':' || content_hash, ',' order by document_id, chunk_index)), '') from document_chunk"
$resolvableSql = "select count(*) from qa_trace_evidence e join document_chunk c on e.chunk_id = c.id::text"
$chunksBefore = @(Sql $chunkFingerprint)[0]
$resolvableBefore = [int](@(Sql $resolvableSql)[0])
$reindex = Call 'POST' '/ingest/reindex?force=true' $adminToken $null
Check '强制重解析返回 200' ($reindex.Status -eq 200) "HTTP $($reindex.Status)"
Check '每份文档都解析成功' ($reindex.Json.failed -eq 0 -and $reindex.Json.total -ge 1) `
    "total=$($reindex.Json.total) indexed=$($reindex.Json.indexed) skipped=$($reindex.Json.skipped) failed=$($reindex.Json.failed)"

$chunksAfter = @(Sql $chunkFingerprint)[0]
$resolvableAfter = [int](@(Sql $resolvableSql)[0])
Check 'A3 强制重解析后片段 id 不变（按 (document_id, chunk_index) 覆盖写，不是删了重灌）' `
    ($chunksBefore -eq $chunksAfter) "重解析前=$chunksBefore 重解析后=$chunksAfter"
Check 'A3 重解析不新增悬空账本引用' ($resolvableAfter -eq $resolvableBefore) `
    "可反查的账本片段：$resolvableBefore → $resolvableAfter（账本片段总数 $(@(Sql "select count(*) from qa_trace_evidence")[0])）"

$badDocs = [int](@(Sql "select count(*) from document where chunk_status <> 'INDEXED' or chunk_count <= 0 or chunk_error is not null")[0])
Check '库内每份文档 chunk_status=INDEXED 且无失败原因' ($badDocs -eq 0) "不满足的文档数=$badDocs"

$badChunks = [int](@(Sql "select count(*) from document_chunk where content is null or embedding is null or kind is null or char_count is null")[0])
Check '每个片段都有正文、向量、类型与字数' ($badChunks -eq 0) "缺字段的片段数=$badChunks"

$models = @(Sql "select embed_model, count(*) from document_chunk group by 1")
Check '向量只有一个模型（换模型=迁移，不能混着用）' ($models.Count -eq 1) ($models -join '；')

$pdfNoPage = [int](@(Sql "select count(*) from document_chunk c join document d on d.id = c.document_id where d.mime_type like '%pdf%' and c.page_from is null")[0])
Check 'PDF 片段都带页码（有页码语义就不许丢）' ($pdfNoPage -eq 0) "缺页码的 PDF 片段数=$pdfNoPage"

$docxPage = [int](@(Sql "select count(*) from document_chunk c join document d on d.id = c.document_id where d.mime_type not like '%pdf%' and c.page_from is not null")[0])
Check 'AC-P2-009 DOCX 片段不带页码（没有就留空，不许瞎填）' ($docxPage -eq 0) "被填上页码的 DOCX 片段数=$docxPage"

Write-Host '  逐份文档：'
@(Sql "select d.doc_no || ' ' || d.version || ' 片段=' || count(*) || ' 带页码=' || count(c.page_from) || ' 章节路径=' || count(c.section_path) from document_chunk c join document d on d.id = c.document_id group by d.doc_no, d.version order by d.doc_no, d.version") |
    ForEach-Object { Write-Host "    $_" }

# ---------- AC-P2-002：幂等 ----------
Write-Host "`n---------- AC-P2-002 幂等 ----------"
$fingerprintSql = "select count(*) || '|' || coalesce(max(id), 0) || '|' || coalesce(md5(string_agg(content_hash, ',' order by document_id, chunk_index)), '') from document_chunk"
$before = @(Sql $fingerprintSql)[0]
$again = Call 'POST' '/ingest/reindex?force=false' $adminToken $null
Check '内容未变时重跑：全部跳过，一条都不重写' ($again.Json.indexed -eq 0 -and $again.Json.skipped -eq $again.Json.total) `
    "indexed=$($again.Json.indexed) skipped=$($again.Json.skipped) total=$($again.Json.total)"
$after = @(Sql $fingerprintSql)[0]
Check '片段数不增、最大 id 不变（没有重算向量、没有换 chunkId）' ($before -eq $after) "重跑前=$before 重跑后=$after"

# ---------- AC-P2-003：页码独立复核 ----------
Write-Host "`n---------- AC-P2-003 页码独立复核（pypdf，与后端 PDFBox 不同实现） ----------"
$pdfTool = Join-Path $PSScriptRoot 'pdf-page-text.py'
$library = Join-Path $root 'knowledge'
$rows = @(Sql @"
select c.id, d.source_path, c.page_from, replace(c.content, '|', '/')
  from document_chunk c
  join document d on d.id = c.document_id
 where c.page_from is not null and c.kind in ('PARAGRAPH', 'BULLET') and length(replace(c.content, ' ', '')) >= 40
 order by c.document_id, c.chunk_index
"@)
# 按文档轮转取样，一份文档最多一条，避免把抽查堆在同一份上
$byDoc = @{}
foreach ($row in $rows) {
    $f = $row -split '\|', 4
    $doc = $f[1]
    if (-not $byDoc.ContainsKey($doc)) { $byDoc[$doc] = @() }
    $byDoc[$doc] += ,@($f[0], $f[1], [int]$f[2], $f[3])
}
$samples = @()
foreach ($doc in ($byDoc.Keys | Sort-Object)) { $samples += ,$byDoc[$doc][0] }
$samples = @($samples | Select-Object -First 6)

$checked = 0
$pageHits = 0
$specific = 0
foreach ($sample in $samples) {
    $chunkId = $sample[0]; $sourcePath = $sample[1]; $page = $sample[2]; $content = $sample[3]
    $file = Join-Path $library ($sourcePath -replace '/', '\')
    if (-not (Test-Path -LiteralPath $file)) {
        Check "chunk $chunkId 的源文件存在" $false $file
        continue
    }
    $pageText = @{}
    foreach ($line in (& python $pdfTool $file 2>&1)) {
        $tab = $line.IndexOf("`t")
        if ($tab -gt 0) {
            $number = 0
            if ([int]::TryParse($line.Substring(0, $tab), [ref]$number)) {
                $pageText[$number] = ($line.Substring($tab + 1) -replace '\s', '')
            }
        }
    }
    $flat = $content -replace '\s', ''
    $window = [Math]::Min(16, $flat.Length)
    $needles = @()
    foreach ($fraction in 0, 0.25, 0.5, 0.75) {
        $start = [int][Math]::Floor(($flat.Length - $window) * $fraction)
        if ($start -lt 0) { $start = 0 }
        $needles += $flat.Substring($start, [Math]::Min($window, $flat.Length - $start))
    }
    $checked++
    $claimed = $pageText[$page]
    $found = $false
    $used = ''
    foreach ($needle in $needles) {
        if ($claimed -and $claimed.Contains($needle)) { $found = $true; $used = $needle; break }
    }
    if ($found) { $pageHits++ }
    $elsewhere = @($pageText.Keys | Where-Object { $_ -ne $page -and $pageText[$_].Contains($needles[0]) }).Count
    if (-not $elsewhere) { $specific++ }
    Check "chunk $chunkId 标称第 $page 页" $found `
        "$(Split-Path -Leaf $file) 命中片段=「$used」；该片段里取出的原文片段只出现在第 $page 页（别处命中数=$elsewhere）"
}

Check '抽查样本数足够（每份 PDF 一条，最多 6 条）' ($checked -ge 3) "抽查 $checked 条，页码命中 $pageHits 条，页码专属 $specific 条"
Check 'AC-P2-003 抽查的片段页码与 PDF 实际页码一致' ($checked -gt 0 -and $pageHits -eq $checked) "$pageHits/$checked 一致"

# ---------- P1 解析预览 ----------
Write-Host "`n---------- P1 解析预览 ----------"
# 预览与入库走同一条解析路径（版面分析可用则用，否则内置），所以预览能代表真实入库结果。
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
$docId = @(Sql "select id from document order by id limit 1")[0]
$preview = Call 'POST' '/ingest/parse-preview' $adminToken @{ documentId = [long]$docId }
Check '管理员可解析预览（200 且返回块）' ($preview.Status -eq 200 -and @($preview.Json.blocks).Count -ge 1) `
    "HTTP $($preview.Status) 块=$(@($preview.Json.blocks).Count) 解析器=$($preview.Json.parser)"
Check '预览带解析器名与块/片段计数' (($preview.Json.parser) -and ($preview.Json.blockCount -ge 1) -and ($preview.Json.chunkCount -ge 1)) `
    "parser=$($preview.Json.parser) blockCount=$($preview.Json.blockCount) chunkCount=$($preview.Json.chunkCount)"
$forbidden = Call 'POST' '/ingest/parse-preview' $demoToken @{ documentId = [long]$docId }
Check '非管理员解析预览被拒（403）' ($forbidden.Status -eq 403) "HTTP $($forbidden.Status)"

# ---------- 汇总 ----------
Write-Host ""
Write-Host ("通过 {0} / {1} 项检查" -f $script:pass, ($script:pass + $script:fail))
if ($script:fail -gt 0) { exit 1 }
