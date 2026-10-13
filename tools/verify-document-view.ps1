# §13 文档查看（原文 / Markdown）验收：AC-D-001 ~ AC-D-010。
#
# 前置：后端 9090 在跑；终端**不受沙箱限制**（要访问 Docker 管道取库内对照值，
# 还要用 java + PDFBox 提取 PDF 文本来核对水印——AC-D-008 明确不接受"看截图"）。
# 用法：pwsh tools\verify-document-view.ps1
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

$outDir = Join-Path $root 'target\acceptance\view'
if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri = "http://127.0.0.1:9090/api$path"; Method = $method; Headers = $headers
        SkipHttpErrorCheck = $true; TimeoutSec = 120
    }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress); $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    $json = $null
    if ($response.Content) { try { $json = $response.Content | ConvertFrom-Json } catch { } }
    return @{ Status = [int]$response.StatusCode; Json = $json; Raw = [string]$response.Content }
}

# 二进制入口：落到文件，只回状态、响应头与体积
function FetchTo($path, $token, $outFile) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:9090/api$path" -Headers $headers `
        -SkipHttpErrorCheck -TimeoutSec 120 -OutFile $outFile -PassThru
    $size = 0
    if (Test-Path -LiteralPath $outFile) { $size = (Get-Item -LiteralPath $outFile).Length }
    return @{
        Status = [int]$response.StatusCode
        Type = [string]($response.Headers['Content-Type'] -join ';')
        Disp = [string]($response.Headers['Content-Disposition'] -join ';')
        Length = $size
    }
}

function Sql([string]$query) {
    $out = & docker exec assistant-pg psql -U assistant -d assistant -t -A -F '|' -c $query 2>&1
    if ($LASTEXITCODE -ne 0) { throw "psql 查询失败：$out`nSQL: $query" }
    return @($out | Where-Object { $_ -ne '' })
}

# PDFBox 从离线仓库里取：不联网、不加依赖（AC-D-008 的核对工具）
$pdfbox = @(
    (Join-Path $env:USERPROFILE '.m2\repository\org\apache\pdfbox\pdfbox\2.0.17\pdfbox-2.0.17.jar'),
    (Join-Path $env:USERPROFILE '.m2\repository\org\apache\pdfbox\fontbox\2.0.17\fontbox-2.0.17.jar'),
    (Join-Path $env:USERPROFILE '.m2\repository\commons-logging\commons-logging\1.2\commons-logging-1.2.jar')
)
$missing = @($pdfbox | Where-Object { -not (Test-Path -LiteralPath $_) })
if ($missing.Count -gt 0) { throw "离线仓库缺少 PDFBox 依赖：$($missing -join '；')" }
$pdfboxCp = $pdfbox -join ';'

function PdfProbe([string]$file, [string]$token) {
    # -Dfile.encoding=UTF-8：Windows 上 JVM 默认按 GBK 输出，脚本按 UTF-8 解码会得到乱码样本
    $out = & java '-Dfile.encoding=UTF-8' -cp $pdfboxCp (Join-Path $PSScriptRoot 'PdfText.java') $file $token 2>&1
    $map = @{}
    foreach ($line in @($out)) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $map[$Matches[1]] = $Matches[2] }
    }
    return $map
}

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++ } else { $script:fail++ }
    $flag = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("{0} {1} —— {2}" -f $flag, $name, $detail)
}

$today = (Get-Date).ToString('yyyy-MM-dd')
$secretDocId = [int](@(Sql "select id from document where doc_no = 'QM-ZD-2026-021'")[0])
$docxDocId = [int](@(Sql "select id from document where doc_no = 'QM-JS-HYZ40-2026'")[0])
$pdfDocId = [int](@(Sql "select id from document where doc_no = 'QM-WX-CNC850-2026'")[0])
$docxHash = [string](@(Sql "select content_hash from document where id = $docxDocId")[0])

$adminToken = (Call 'POST' '/auth/login' $null @{ username = 'admin'; password = (EnvValue 'ASSISTANT_ADMIN_PASSWORD') }).Json.token
$demoToken = (Call 'POST' '/auth/login' $null @{ username = 'demo'; password = (EnvValue 'ASSISTANT_DEMO_PASSWORD') }).Json.token
Check '登录取令牌（admin / demo）' ($adminToken -and $demoToken) "秘密文档 id=$secretDocId；DOCX id=$docxDocId；PDF id=$pdfDocId"

# ---------- AC-D-010 前置快照：查看必须只读 ----------
$snapBefore = [string](@(Sql "select content_hash || '|' || chunk_status || '|' || chunk_count from document where id = $docxDocId")[0])
$chunksBefore = [int](@(Sql "select count(*) from document_chunk where document_id = $docxDocId")[0])

# ---------- AC-D-001 无权限：三入口都 403 ----------
Write-Host "`n---------- AC-D-001 三入口一致拒绝（demo 对秘密文档） ----------"
$f1 = FetchTo "/documents/$secretDocId/file" $demoToken (Join-Path $outDir 'denied-file.bin')
$f2 = Call 'GET' "/documents/$secretDocId/content" $demoToken $null
$f3 = FetchTo "/documents/$secretDocId/download" $demoToken (Join-Path $outDir 'denied-download.bin')
Check 'file 入口 403' ($f1.Status -eq 403) "HTTP $($f1.Status)"
Check 'content 入口 403' ($f2.Status -eq 403) "HTTP $($f2.Status)"
Check 'download 入口 403' ($f3.Status -eq 403) "HTTP $($f3.Status)"
$deniedBody = (Get-Content -LiteralPath (Join-Path $outDir 'denied-file.bin') -Raw -ErrorAction SilentlyContinue)
$leak = ($f2.Raw -match '薪酬') -or ($f2.Raw -match 'QM-ZD-2026-021') -or ($deniedBody -match '薪酬') -or ($f2.Raw -match 'source_path')
Check '403 响应不含标题/编号/内部字段' (-not $leak) "file 响应=$deniedBody"
$deniedRows = [int](@(Sql "select count(*) from document_view_log where document_id = $secretDocId and username = 'demo' and outcome = 'DENIED'")[0])
Check 'AC-D-007 拒绝也留审计行' ($deniedRows -ge 3) "DENIED 行数=$deniedRows（三条入口各一行）"

# 未登录：三入口都应 401
$anon1 = FetchTo "/documents/$pdfDocId/file" $null (Join-Path $outDir 'anon.bin')
Check '未登录访问 file 入口 401' ($anon1.Status -eq 401) "HTTP $($anon1.Status)"

# ---------- AC-D-002 / AC-D-008 PDF：预览与下载都是带水印的 PDF ----------
Write-Host "`n---------- AC-D-002 / AC-D-008 PDF 预览与下载 ----------"
$previewPdf = Join-Path $outDir 'preview.pdf'
$p1 = FetchTo "/documents/$pdfDocId/file" $adminToken $previewPdf
Check 'AC-D-002 预览返回 200 且 Content-Type=application/pdf' ($p1.Status -eq 200 -and $p1.Type -like 'application/pdf*') "HTTP $($p1.Status)，Type=$($p1.Type)"
Check 'AC-D-002 预览是内联展示（Content-Disposition: inline）' ($p1.Disp -like 'inline*') $p1.Disp
$probePreview = PdfProbe $previewPdf "admin $today"
Check 'AC-D-008 预览件提取文本含水印' ($probePreview['HAS_TOKEN'] -eq 'true') "页数=$($probePreview['PAGES'])，样本=$($probePreview['SAMPLE'])"

$downloadPdf = Join-Path $outDir 'download.pdf'
$p2 = FetchTo "/documents/$pdfDocId/download" $adminToken $downloadPdf
Check '下载返回 200 且 Content-Type=application/pdf' ($p2.Status -eq 200 -and $p2.Type -like 'application/pdf*') "HTTP $($p2.Status)，Type=$($p2.Type)"
Check '下载是附件（Content-Disposition: attachment，文件名 .pdf）' (($p2.Disp -like 'attachment*') -and ($p2.Disp -match '\.pdf')) $p2.Disp
$probeDownload = PdfProbe $downloadPdf "admin $today"
Check 'AC-D-008 下载件提取文本含水印' ($probeDownload['HAS_TOKEN'] -eq 'true') "页数=$($probeDownload['PAGES'])"
# 不比对文件哈希：PDFBox 每次保存都会生成新的 /ID，字节必然不同；
# 要保证的是"两条路看到的内容一致"，所以比提取出来的正文与水印。
Check 'AC-D-002 预览与下载内容一致（同一水印、同一正文）' (($probePreview['SAMPLE'] -eq $probeDownload['SAMPLE']) -and $probeDownload['HAS_TOKEN'] -eq 'true') "预览 $($probePreview['CHARS']) 字，下载 $($probeDownload['CHARS']) 字"

# ---------- AC-D-003 DOCX：统一转 PDF，正文与表格可读 ----------
Write-Host "`n---------- AC-D-003 DOCX 原文（转 PDF） ----------"
$docxPdf = Join-Path $outDir 'docx.pdf'
$p3 = FetchTo "/documents/$docxDocId/file" $adminToken $docxPdf
Check 'DOCX 原文入口返回 application/pdf' ($p3.Status -eq 200 -and $p3.Type -like 'application/pdf*') "HTTP $($p3.Status)，Type=$($p3.Type)，字节=$($p3.Length)"
$probeDocx = PdfProbe $docxPdf "admin $today"
Check 'DOCX 转出的 PDF 含水印' ($probeDocx['HAS_TOKEN'] -eq 'true') "页数=$($probeDocx['PAGES'])"
# 用库里的片段原文反查转换结果：转换丢没丢内容，只有对着原文比才知道
$docxChunk = [string](@(Sql "select content from document_chunk where document_id = $docxDocId and length(content) > 30 order by chunk_index limit 1")[0])
$probeChunk = PdfProbe $docxPdf $docxChunk
Check 'DOCX 转出的 PDF 正文可读（与库内片段逐字一致）' ($probeChunk['HAS_TOKEN'] -eq 'true') "抽查片段：$($docxChunk.Substring(0, [Math]::Min(40, $docxChunk.Length)))…"
$docxDownload = Join-Path $outDir 'docx-download.pdf'
$p4 = FetchTo "/documents/$docxDocId/download" $adminToken $docxDownload
Check 'DOCX 下载统一给 PDF（不再回 .docx）' (($p4.Status -eq 200) -and ($p4.Type -like 'application/pdf*') -and ($p4.Disp -match '\.pdf')) "HTTP $($p4.Status)，Disp=$($p4.Disp)"

# ---------- AC-D-004 Markdown 视图：章节层级与页码与源文档一致 ----------
Write-Host "`n---------- AC-D-004 Markdown 视图 ----------"
$md = Call 'GET' "/documents/$docxDocId/content" $adminToken $null
Check 'Markdown 视图返回 200' ($md.Status -eq 200) "HTTP $($md.Status)，片段数=$($md.Json.chunkCount)"
$mdSections = @(Sql "select distinct section_path from document_chunk where document_id = $docxDocId and section_path is not null and section_path <> '' order by 1")
$missingSections = @($mdSections | Where-Object { $md.Json.markdown -notmatch [regex]::Escape('# ' + $_) -and $_ -ne $md.Json.title })
Check 'Markdown 章节标题覆盖库内全部 section_path（独立对照）' ($missingSections.Count -eq 0) "库内 $($mdSections.Count) 个小节，缺失：$($missingSections -join '；')"
# 页码锚点：与库内 page_from 逐条对照（抽 3 处）
$pageRows = @(Sql "select id || '|' || coalesce(page_from::text,'') from document_chunk where document_id = $pdfDocId and page_from is not null order by chunk_index limit 3")
$pdfMd = Call 'GET' "/documents/$pdfDocId/content" $adminToken $null
$pageMismatch = @()
foreach ($row in $pageRows) {
    $parts = $row -split '\|'
    if ($pdfMd.Json.pageOfChunk.($parts[0]) -ne [int]$parts[1]) { $pageMismatch += "$($parts[0]) 库内=$($parts[1]) 接口=$($pdfMd.Json.pageOfChunk.($parts[0]))" }
}
Check 'Markdown 页码锚点与库内 page_from 一致（抽查 3 处）' ($pageMismatch.Count -eq 0) "抽查 $($pageRows.Count) 处，不符：$($pageMismatch -join '；')"
$sampleChunk = [string](@(Sql "select content from document_chunk where document_id = $pdfDocId and length(content) > 30 order by chunk_index limit 1")[0])
Check 'Markdown 正文与库内片段一致（抽查 1 处）' ($pdfMd.Json.markdown -match [regex]::Escape($sampleChunk.Substring(0, [Math]::Min(30, $sampleChunk.Length)))) $sampleChunk.Substring(0, [Math]::Min(30, $sampleChunk.Length))

# ---------- AC-D-005 引用跳原文：锚点存在 ----------
Write-Host "`n---------- AC-D-005 引用跳原文 ----------"
# 锚点覆盖按**当前**片段对照库内 id：账本里的 chunk_id 可能是强制重解析前留下的旧 id（偏离 A3），
# 拿它当判据会把"历史欠账"误报成"功能坏了"。引用点击的落地效果由界面截图脚本负责。
$dbChunkIds = @(Sql "select id from document_chunk where document_id = $pdfDocId order by chunk_index")
$apiChunkIds = @($pdfMd.Json.blocks | ForEach-Object { [string]$_.chunkId })
$missingAnchors = @($dbChunkIds | Where-Object { $apiChunkIds -notcontains $_ })
Check 'Markdown 视图为库内每个片段都提供了锚点（可被引用点击定位）' ($missingAnchors.Count -eq 0) "库内 $($dbChunkIds.Count) 个片段，缺锚点 $($missingAnchors.Count) 个"

# ---------- AC-D-006 不泄漏内部信息 ----------
Write-Host "`n---------- AC-D-006 响应不泄漏内部信息 ----------"
$bodies = @($md.Json.markdown, $pdfMd.Json.markdown, $pdfMd.Raw, (Get-Content -LiteralPath (Join-Path $outDir 'denied-file.bin') -Raw))
$pdfText = (PdfProbe $previewPdf '')['SAMPLE']
$leakPatterns = @('source_path', 'sourcePath', 'contentHash', 'content_hash', 'D:\\', 'knowledge\\')
$found = @()
foreach ($body in $bodies) {
    foreach ($pattern in $leakPatterns) { if ($body -match [regex]::Escape($pattern)) { $found += $pattern } }
}
foreach ($pattern in @('D:\\', $docxHash.Substring(0, 16))) {
    if ($pdfText -match [regex]::Escape($pattern)) { $found += "PDF:$pattern" }
}
Check '响应体与 PDF 文本都不含路径/哈希/内核 id' ($found.Count -eq 0) "命中：$((@($found | Select-Object -Unique)) -join '；')"

# ---------- AC-D-007 三种入口各留一行审计 ----------
Write-Host "`n---------- AC-D-007 审计 ----------"
foreach ($channel in @('FILE', 'CONTENT', 'DOWNLOAD')) {
    $rows = @(Sql "select username || '|' || coalesce(watermark,'') || '|' || role_code from document_view_log where document_id in ($pdfDocId, $docxDocId) and channel = '$channel' and outcome = 'OK' order by id desc limit 1")
    $ok = ($rows.Count -ge 1) -and ($rows[0] -like "admin|admin $today|admin")
    Check "入口 $channel 留审计行（账号/水印/角色）" $ok ($rows -join '；')
}

# ---------- AC-D-009 大文件限流 ----------
Write-Host "`n---------- AC-D-009 大文件拒绝策略 ----------"
$probeDir = Join-Path $root 'knowledge\_probe'
$bigFile = Join-Path $probeDir 'oversize.pdf'
$probeId = 0
try {
    New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
    $head = "%PDF-1.4`n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj`n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj`n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj`ntrailer<</Root 1 0 R>>`n"
    $stream = [System.IO.File]::Create($bigFile)
    try {
        $headBytes = [System.Text.Encoding]::ASCII.GetBytes($head)
        $stream.Write($headBytes, 0, $headBytes.Length)
        $pad = [System.Text.Encoding]::ASCII.GetBytes('%% 填充：这一份只用来验证体积上限的拒绝策略 ' + ('0' * 512) + "`n")
        for ($i = 0; $i -lt 43000; $i++) { $stream.Write($pad, 0, $pad.Length) }
    } finally { $stream.Dispose() }
    $bigSize = (Get-Item -LiteralPath $bigFile).Length
    Sql "insert into document (domain_code, doc_no, title, version, secrecy, source_path, content_hash, file_size, mime_type) values ('policy', '__PROBE-BIG', '大文件探针', 'V0', '内部公开', '_probe/oversize.pdf', repeat('0', 64), $bigSize, 'application/pdf')" | Out-Null
    $probeId = [int](@(Sql "select id from document where doc_no = '__PROBE-BIG'")[0])
    Sql "insert into document_acl (document_id, subject_type, subject_value, permission) values ($probeId, 'ALL', '*', 'READ') on conflict do nothing" | Out-Null
    $bigProbe = FetchTo "/documents/$probeId/file" $adminToken (Join-Path $outDir 'oversize.bin')
    $bigBody = Get-Content -LiteralPath (Join-Path $outDir 'oversize.bin') -Raw -ErrorAction SilentlyContinue
    Check '超过 20MB 时明确拒绝（HTTP 413 + 可读原因），而不是爆内存' ($bigProbe.Status -eq 413) "文件 $([int]($bigSize / 1024 / 1024))MB，HTTP $($bigProbe.Status)：$bigBody"
} finally {
    if ($probeId -gt 0) { Sql "delete from document where id = $probeId" | Out-Null }
    if (Test-Path -LiteralPath $bigFile) { Remove-Item -LiteralPath $bigFile -Force }
    if (Test-Path -LiteralPath $probeDir) { Remove-Item -LiteralPath $probeDir -Force -ErrorAction SilentlyContinue }
}

# ---------- AC-D-010 查看是只读动作 ----------
Write-Host "`n---------- AC-D-010 查看不动库里的值 ----------"
$snapAfter = [string](@(Sql "select content_hash || '|' || chunk_status || '|' || chunk_count from document where id = $docxDocId")[0])
$chunksAfter = [int](@(Sql "select count(*) from document_chunk where document_id = $docxDocId")[0])
Check 'content_hash / chunk_status / chunk_count 不变' ($snapBefore -eq $snapAfter) "前=$snapBefore`n                                                     后=$snapAfter"
Check 'document_chunk 行数不变' ($chunksBefore -eq $chunksAfter) "前=$chunksBefore 后=$chunksAfter"

''
Write-Host ("通过 {0} 项，失败 {1} 项；产物在 {2}" -f $script:pass, $script:fail, $outDir)
if ($script:fail -gt 0) { exit 1 }
