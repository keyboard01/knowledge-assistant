package com.hykj.assistant.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hykj.assistant.adapters.anythingllm.KernelGateway;
import com.hykj.assistant.domain.DomainRepository;
import com.hykj.assistant.domain.KnowledgeDomain;
import com.hykj.assistant.parse.ChunkSplitter;
import com.hykj.assistant.parse.DocumentParseService;
import com.hykj.assistant.parse.ParsedBlock;
import com.hykj.assistant.settings.SettingService;

/**
 * 同步流水线：目录扫描 → 内容哈希 → 幂等判定 → 上传 → 状态与重试。
 *
 * 三条不变量：
 * 1. 内容没变就绝不重复上传（内容哈希 + 内核已有同版本同内容）——否则向量会成倍增长；
 * 2. 每次尝试都留痕，失败原因必须能让人看懂、能直接重试；
 * 3. 一个文档要么"已入库"，要么"标失败"，不允许留下半成品。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public record FileResult(String sourcePath, String action, String status, String detail,
                             String domainCode, Long documentId) {
    }

    public record ScanSummary(String sourceRoot, String startedAt, int scanned, int uploaded, int skipped,
                              int failed, Integer vectorCountBefore, Integer vectorCountAfter,
                              List<FileResult> files) {
    }

    private final DocumentRepository documents;
    private final IngestTaskRepository tasks;
    private final DomainRepository domains;
    private final SettingService settingService;
    private final KernelGateway kernel;
    private final ChunkIndexer chunkIndexer;
    private final DocumentParseService parser;

    public IngestService(DocumentRepository documents, IngestTaskRepository tasks, DomainRepository domains,
                         SettingService settingService, KernelGateway kernel, ChunkIndexer chunkIndexer,
                         DocumentParseService parser) {
        this.documents = documents;
        this.tasks = tasks;
        this.domains = domains;
        this.settingService = settingService;
        this.kernel = kernel;
        this.chunkIndexer = chunkIndexer;
        this.parser = parser;
    }

    public Path sourceRoot() {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_INGEST);
        String configured = cfg.get("sourceRoot");
        String value = configured == null || configured.isBlank() ? "knowledge" : configured.trim();
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    public ScanSummary scan() {
        Path root = sourceRoot();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("文档根目录不存在：" + root);
        }
        IngestManifest.Loaded manifest;
        try {
            manifest = IngestManifest.load(root);
        } catch (IOException ex) {
            throw new IllegalStateException("读取文档登记表失败：" + ex.getMessage(), ex);
        }
        Map<String, KnowledgeDomain> domainsByCode = domainsByCode();

        Integer vectorBefore = vectorCount();
        List<FileResult> results = new ArrayList<>();
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        log.info("[同步] 开始扫描 {}（登记 {} 份）", root, manifest.byRelativePath().size());

        for (Path file : listFiles(root)) {
            String relative = IngestManifest.normalize(root.relativize(file).toString());
            IngestManifest.Entry entry = manifest.byRelativePath().get(relative);
            FileResult result;
            if (entry == null) {
                result = skipUnregistered(relative);
            } else if (entry.docNo() == null || entry.docNo().isBlank()
                    || entry.version() == null || entry.version().isBlank()) {
                result = failTask(relative, entry.domainCode(), "登记表缺少 docNo 或 version，无法入库");
            } else {
                result = ingest(root, file, relative, entry, domainsByCode);
            }
            results.add(result);
            switch (result.status()) {
                case "SUCCEEDED" -> uploaded++;
                case "FAILED" -> failed++;
                default -> skipped++;
            }
        }

        Integer vectorAfter = vectorCount();
        log.info("[同步] 完成：扫描 {}，入库 {}，跳过 {}，失败 {}；内核向量 {} → {}",
                results.size(), uploaded, skipped, failed, vectorBefore, vectorAfter);
        return new ScanSummary(root.toString(), OffsetDateTime.now().toString(), results.size(),
                uploaded, skipped, failed, vectorBefore, vectorAfter, results);
    }

    /** 按任务重试：重新读登记表，重新算哈希，走同一条路径。 */
    public FileResult retry(long taskId) {
        IngestTaskRepository.Row task = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        Path root = sourceRoot();
        Path file = root.resolve(task.sourcePath()).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("任务路径越出文档根目录：" + task.sourcePath());
        }
        if (!Files.isRegularFile(file)) {
            return failTask(task.sourcePath(), null, "源文件已不存在：" + file);
        }
        IngestManifest.Loaded manifest;
        try {
            manifest = IngestManifest.load(root);
        } catch (IOException ex) {
            throw new IllegalStateException("读取文档登记表失败：" + ex.getMessage(), ex);
        }
        IngestManifest.Entry entry = manifest.byRelativePath().get(task.sourcePath());
        if (entry == null) {
            return failTask(task.sourcePath(), null, "该文件未在 manifest.json 登记，无法重试");
        }
        return ingest(root, file, task.sourcePath(), entry, domainsByCode());
    }

    public List<FileResult> retryFailed() {
        List<String> paths = tasks.failedSourcePaths();
        List<FileResult> results = new ArrayList<>();
        for (String sourcePath : paths) {
            IngestTaskRepository.Row task = tasks.latestForPath(sourcePath).orElse(null);
            if (task == null) {
                continue;
            }
            results.add(retry(task.id()));
        }
        log.info("[同步] 重试失败项 {} 个", results.size());
        return results;
    }

    /** 浏览器上传的载荷（文件已读成字节，元数据来自表单）。 */
    public record UploadRequest(String domainCode, String docNo, String title, String version, String docStatus,
                                String secrecy, String ownerDept, String effectiveFrom, String effectiveTo,
                                String originalFilename, byte[] content) {
    }

    /**
     * 浏览器上传：写盘 → 登记 manifest.json → 走与扫描同一条入库链路（内核上传 + 自建解析 + 按密级写 ACL）。
     *
     * <p>与"目录 + manifest 手工登记"是同一条链的两种入口：上传只是把"放文件 + 写登记"这两步搬到了服务端。
     * 目标路径固定为 {@code {domainCode}/{docNo}_{version}{ext}}（ASCII 清洗），所以同一版本重复上传是覆盖同一路径。</p>
     */
    public FileResult upload(UploadRequest request) {
        String domainCode = require(request.domainCode(), "请选择知识域");
        KnowledgeDomain domain = domainsByCode().get(domainCode);
        if (domain == null) {
            throw new IllegalArgumentException("知识域不存在：" + domainCode);
        }
        if (!domain.enabled()) {
            throw new IllegalStateException("知识域已停用：" + domainCode);
        }
        String docNo = require(request.docNo(), "文档编号不能为空");
        String title = require(request.title(), "文档标题不能为空");
        String version = require(request.version(), "版本号不能为空");
        String extension = extension(request.originalFilename());
        if (!".pdf".equals(extension) && !".docx".equals(extension)) {
            throw new IllegalArgumentException("只支持上传 PDF / DOCX（当前：" + extension + "）");
        }
        if (request.content() == null || request.content().length == 0) {
            throw new IllegalArgumentException("上传的文件为空");
        }
        ensureWorkspace(domain);

        Path root = sourceRoot();
        String relative = IngestManifest.normalize(
                domainCode + "/" + safe(docNo) + "_" + safe(version) + extension);
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("目标路径越出文档根目录：" + relative);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, request.content());
        } catch (IOException ex) {
            throw new IllegalStateException("写入文件失败：" + ex.getMessage(), ex);
        }

        IngestManifest.Entry entry = new IngestManifest.Entry(relative, domainCode, docNo, title, version,
                blankTo(request.docStatus(), "现行"), blankTo(request.secrecy(), "内部公开"),
                request.ownerDept(), request.effectiveFrom(), request.effectiveTo());
        String replaced;
        try {
            replaced = ManifestWriter.upsert(root, entry);
        } catch (IOException ex) {
            throw new IllegalStateException("登记 manifest.json 失败：" + ex.getMessage(), ex);
        }
        if (replaced != null) {
            try {
                Files.deleteIfExists(root.resolve(replaced).normalize());
            } catch (IOException ex) {
                log.warn("替换版本后清理旧文件失败（不影响入库）：{} —— {}", replaced, ex.getMessage());
            }
        }
        return ingestOne(relative);
    }

    /** 入库单个已登记文件（供上传与按需单文件重入使用）。 */
    public FileResult ingestOne(String relative) {
        Path root = sourceRoot();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("路径越出文档根目录：" + relative);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在：" + relative);
        }
        IngestManifest.Loaded manifest;
        try {
            manifest = IngestManifest.load(root);
        } catch (IOException ex) {
            throw new IllegalStateException("读取文档登记表失败：" + ex.getMessage(), ex);
        }
        IngestManifest.Entry entry = manifest.byRelativePath().get(IngestManifest.normalize(relative));
        if (entry == null) {
            throw new IllegalStateException("文件未在 manifest.json 登记：" + relative);
        }
        return ingest(root, file, IngestManifest.normalize(relative), entry, domainsByCode());
    }

    /** 上传直接打到内核，工作区不存在会失败得莫名其妙——先明确报出来，指路"初始化"。 */
    private void ensureWorkspace(KnowledgeDomain domain) {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_ANYTHINGLLM);
        if (!kernel.hasApiKey(cfg)) {
            throw new IllegalStateException("未配置内核 API Key，无法上传");
        }
        try {
            boolean exists = kernel.listWorkspaces(cfg).stream()
                    .anyMatch(workspace -> workspace.slug().equals(domain.workspaceSlug()));
            if (!exists) {
                throw new IllegalStateException("知识域「" + domain.name() + "」的内核工作区还没初始化，"
                        + "请先在「知识域与内核」里执行初始化");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检查内核工作区被中断");
        } catch (IOException ex) {
            throw new IllegalStateException("连接内核失败：" + ex.getMessage(), ex);
        }
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** 文件名清洗：文档编号/版本可能带奇怪字符，落到文件名里只留 ASCII 安全字符。 */
    private static String safe(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.isEmpty() ? "doc" : cleaned;
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private FileResult ingest(Path root, Path file, String relative, IngestManifest.Entry entry,
                              Map<String, KnowledgeDomain> domainsByCode) {
        KnowledgeDomain domain = domainsByCode.get(entry.domainCode());
        if (domain == null) {
            return failTask(relative, entry.domainCode(), "登记的知识域不存在：" + entry.domainCode());
        }
        if (!domain.enabled()) {
            return failTask(relative, domain.code(), "知识域已停用：" + domain.code());
        }

        Map<String, String> cfg = settingService.effective(SettingService.GROUP_ANYTHINGLLM);
        if (!kernel.hasApiKey(cfg)) {
            return failTask(relative, domain.code(), "未配置内核 API Key，无法上传");
        }

        byte[] content;
        String hash;
        try {
            content = Files.readAllBytes(file);
            hash = sha256(content);
        } catch (IOException ex) {
            return failTask(relative, domain.code(), "读取源文件失败：" + ex.getMessage());
        }

        String mimeType = mimeType(file);
        String kernelName = kernelDocName(entry, file);
        long taskId = tasks.insert(relative, hash, tasks.latestAttempts(relative) + 1);

        DocumentRepository.Row existing = documents.findByDocNoAndVersion(entry.docNo(), entry.version()).orElse(null);
        if (existing != null && hash.equals(existing.contentHash()) && "INDEXED".equals(existing.indexStatus())) {
            String metadataDetail = documents.syncMetadata(existing, entry, domain.code());
            String detail = "内容未变化，内核已有同版本同内容（幂等跳过）"
                    + (metadataDetail.isEmpty() ? "" : "；" + metadataDetail);
            tasks.finish(taskId, existing.id(), "SKIPPED", "SKIPPED", detail, null);
            return new FileResult(relative, "SKIPPED", "SKIPPED", detail, domain.code(), existing.id());
        }

        // 动作要能还原真实经过：首次登记 / 改了内容重新入库 / 只是上次没成功再试一次
        String action;
        if (existing == null) {
            action = "CREATED";
        } else if ("INDEXED".equals(existing.indexStatus())) {
            action = "REUPLOADED";
        } else {
            action = "RETRIED";
        }
        long documentId;
        if (existing == null) {
            documentId = documents.insert(entry, domain.code(), relative, hash, content.length, mimeType, kernelName);
        } else {
            documentId = existing.id();
            try {
                // 同一 (文档编号, 版本) 的内容变了，先把内核里的旧副本删掉：
                // 先删后传，任何时刻都不会同时存在两份内容。
                if (existing.anythingllmDocId() != null && !existing.anythingllmDocId().isBlank()) {
                    kernel.removeDocuments(cfg, List.of(existing.anythingllmDocId()));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return finishFailure(taskId, documentId, relative, domain.code(), action, "清理旧版本被中断");
            } catch (Exception ex) {
                return finishFailure(taskId, documentId, relative, domain.code(), action,
                        "清理内核旧版本失败：" + describeFailure(ex));
            }
            documents.updateSource(documentId, entry, domain.code(), relative, hash, content.length, mimeType, kernelName);
        }

        try {
            Integer beforeUpload = vectorCount();
            var uploaded = kernel.uploadDocument(cfg, domain.workspaceSlug(), kernelName, mimeType, content,
                    metadata(entry, domain));
            Integer afterUpload = vectorCount();
            if (beforeUpload != null && afterUpload != null && afterUpload <= beforeUpload) {
                throw new IllegalStateException("内核收下了文件却没有产生向量（" + beforeUpload + " → " + afterUpload
                        + "），请检查内核向量化配置与批量上限");
            }
            documents.markIndexed(documentId, uploaded.location());
            documents.replaceAcl(documentId, entry.secrecy());
            String delta = beforeUpload == null || afterUpload == null ? ""
                    : "，新增向量 " + (afterUpload - beforeUpload);
            // 内核收下 ≠ 自建片段就绪：这一步失败不影响"文档已入库"，但会如实标出来
            String detail = "已入库 " + kernelName + delta + indexChunks(documentId, entry.title(), file);
            tasks.finish(taskId, documentId, "SUCCEEDED", action, detail, null);
            log.info("[同步] {} {} → {}", action, relative, detail);
            return new FileResult(relative, action, "SUCCEEDED", detail, domain.code(), documentId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            documents.markFailed(documentId, "上传被中断");
            tasks.finish(taskId, documentId, "FAILED", action, "上传被中断", "上传被中断");
            return new FileResult(relative, action, "FAILED", "上传被中断", domain.code(), documentId);
        } catch (Exception ex) {
            String message = "上传到内核失败：" + describeFailure(ex);
            documents.markFailed(documentId, message);
            tasks.finish(taskId, documentId, "FAILED", action, "上传失败", message);
            log.error("[同步] {} {} 失败：{}", action, relative, message);
            return new FileResult(relative, action, "FAILED", message, domain.code(), documentId);
        }
    }

    private FileResult finishFailure(long taskId, long documentId, String relative, String domainCode,
                                     String action, String message) {
        documents.markFailed(documentId, message);
        tasks.finish(taskId, documentId, "FAILED", action, "上传前清理失败", message);
        log.error("[同步] {} {} 失败：{}", action, relative, message);
        return new FileResult(relative, action, "FAILED", message, domainCode, documentId);
    }

    private FileResult skipUnregistered(String relative) {
        String detail = "未在 manifest.json 登记，已跳过（不接受匿名入库）";
        long taskId = tasks.insert(relative, null, tasks.latestAttempts(relative) + 1);
        tasks.finish(taskId, null, "SKIPPED", "SKIPPED", detail, null);
        log.info("[同步] SKIPPED {} —— {}", relative, detail);
        return new FileResult(relative, "SKIPPED", "SKIPPED", detail, null, null);
    }

    private FileResult failTask(String relative, String domainCode, String message) {
        long taskId = tasks.insert(relative, null, tasks.latestAttempts(relative) + 1);
        tasks.finish(taskId, null, "FAILED", "FAILED", message, message);
        log.error("[同步] FAILED {} —— {}", relative, message);
        return new FileResult(relative, "FAILED", "FAILED", message, domainCode, null);
    }

    private Map<String, KnowledgeDomain> domainsByCode() {
        Map<String, KnowledgeDomain> index = new HashMap<>();
        domains.findAll().forEach(domain -> index.put(domain.code(), domain));
        return index;
    }

    private Integer vectorCount() {
        try {
            return kernel.vectorCount(settingService.effective(SettingService.GROUP_ANYTHINGLLM));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.debug("读取内核向量数失败：{}", ex.getMessage());
            return null;
        }
    }

    private static Map<String, String> metadata(IngestManifest.Entry entry, KnowledgeDomain domain) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (entry.ownerDept() != null && !entry.ownerDept().isBlank()) {
            metadata.put("docAuthor", entry.ownerDept().trim());
        }
        metadata.put("description", entry.title() + " " + entry.version() + " " + blank(entry.docStatus())
                + " 密级：" + blank(entry.secrecy()));
        metadata.put("docSource", "企业知识助手同步：" + domain.name() + "（" + domain.code() + "）");
        return metadata;
    }

    /**
     * 内核里的文档名。检索命中只带得回文档名，带回不了我们的主键，
     * 所以这个名字必须既能反查、又只含 ASCII（中文名在 multer 里会变乱码）。
     */
    private static String kernelDocName(IngestManifest.Entry entry, Path file) {
        return ascii(entry.docNo()) + "_" + ascii(entry.version()) + extension(file);
    }

    private static String ascii(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static List<Path> listFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(IngestService::isIngestable)
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("遍历文档目录失败：" + ex.getMessage(), ex);
        }
    }

    private static boolean isIngestable(Path path) {
        String name = path.getFileName().toString();
        if (name.equalsIgnoreCase("manifest.json")) {
            return false;
        }
        // Office 打开文档时会留下 ~$xxx.docx 临时文件，隐藏文件同理
        return !name.startsWith(".") && !name.startsWith("~$");
    }

    private static String mimeType(Path file) {
        return switch (extension(file)) {
            case ".pdf" -> "application/pdf";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".doc" -> "application/msword";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".txt" -> "text/plain";
            case ".md" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }

    static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不提供 SHA-256", ex);
        }
    }

    /**
     * 失败原因是要给人看、给人照着修的，不是打印异常类名。
     * 连接类异常单看 "ClosedChannelException" 谁也判断不出该做什么。
     */
    /**
     * 解析 + 切分 + 向量化。失败不吞掉、也不把整份文档标失败——
     * 内核那边确实收下了，把它标成 FAILED 是另一种谎。片段侧单独标失败。
     */
    private String indexChunks(long documentId, String documentTitle, java.nio.file.Path file) {
        try {
            ChunkIndexer.IndexResult result = chunkIndexer.index(documentId, documentTitle, file, false);
            documents.markChunkIndexed(documentId, result.chunks());
            return "；" + result.detail();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            documents.markChunkFailed(documentId, "解析被中断");
            return "；自建片段未就绪：解析被中断";
        } catch (Exception ex) {
            String message = describeFailure(ex);
            documents.markChunkFailed(documentId, message);
            log.warn("[片段] 文档 {} 解析/向量化失败：{}", documentId, message);
            return "；自建片段未就绪：" + message;
        }
    }

    public record ReindexSummary(int total, int indexed, int skipped, int failed,
                                 List<ChunkIndexer.IndexResult> results) {
    }

    /**
     * 解析预览（P1 解析升级）：把某份文档解析出的**块**列给人看，让"解析对不对"可被人工确认，
     * 而不是等检索答错才发现。
     */
    public record ParsePreview(long documentId, String docNo, String title, String parser, int blockCount,
                               int chunkCount, boolean truncated, List<Block> blocks) {

        public record Block(String kind, int page, int level, String text) {
        }
    }

    /** 预览用块数上限：够看出解析质量，又不至于让一次响应堆到几十万字。 */
    private static final int PREVIEW_BLOCK_LIMIT = 200;

    /**
     * 解析某份文档并返回块级预览。与入库走**同一条解析路径**（版面分析可用则用，否则内置），
     * 所以"预览看着对、入库却是别的结果"这种情况不会发生。
     */
    public ParsePreview preview(long documentId) {
        DocumentRepository.Row row = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在：" + documentId));
        Path root = sourceRoot();
        Path file = root.resolve(row.sourcePath()).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("文档路径越出文档根目录：" + row.sourcePath());
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("源文件不存在：" + root.relativize(file));
        }
        DocumentParseService.Result parsed;
        try {
            parsed = parser.parse(file);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("解析被中断");
        } catch (IOException ex) {
            throw new IllegalStateException("解析失败：" + ex.getMessage(), ex);
        }
        List<ParsedBlock> all = parsed.blocks();
        List<ParsePreview.Block> blocks = all.stream().limit(PREVIEW_BLOCK_LIMIT)
                .map(block -> new ParsePreview.Block(block.kind().name(), block.page(), block.level(), block.text()))
                .toList();
        return new ParsePreview(documentId, row.docNo(), row.title(), parsed.parser(), all.size(),
                ChunkSplitter.split(all).size(), all.size() > PREVIEW_BLOCK_LIMIT, blocks);
    }

    /** 重建全部已入库文档的片段（解析器或分块规则改了以后用；内容没变的会被跳过）。 */
    public ReindexSummary reindex(boolean force) {
        Path root = sourceRoot();
        List<ChunkIndexer.IndexResult> results = new ArrayList<>();
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        for (DocumentRepository.Row row : documents.findIndexed()) {
            Path file = root.resolve(row.sourcePath()).normalize();
            try {
                if (!Files.isRegularFile(file)) {
                    throw new IllegalStateException("源文件不存在：" + root.relativize(file));
                }
                ChunkIndexer.IndexResult result = chunkIndexer.index(row.id(), row.title(), file, force);
                if ("INDEXED".equals(result.action())) {
                    documents.markChunkIndexed(row.id(), result.chunks());
                    indexed++;
                } else {
                    skipped++;
                }
                results.add(result);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                documents.markChunkFailed(row.id(), "重解析被中断");
                results.add(new ChunkIndexer.IndexResult(row.id(), "FAILED", 0, "重解析被中断"));
                failed++;
            } catch (Exception ex) {
                String message = describeFailure(ex);
                documents.markChunkFailed(row.id(), message);
                results.add(new ChunkIndexer.IndexResult(row.id(), "FAILED", 0, message));
                failed++;
            }
        }
        log.info("[片段] 重解析完成：共 {}，重建 {}，跳过 {}，失败 {}", results.size(), indexed, skipped, failed);
        return new ReindexSummary(results.size(), indexed, skipped, failed, results);
    }

    private static String describeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current instanceof java.net.ConnectException) {
            return "内核拒绝连接（服务未启动或地址端口不对）";
        }
        if (current instanceof java.nio.channels.ClosedChannelException) {
            return "与内核的连接被关闭（内核正在重启或已停止）";
        }
        if (current instanceof java.net.http.HttpTimeoutException) {
            return "等待内核响应超时（文档过大或内核正在忙）";
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message;
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
