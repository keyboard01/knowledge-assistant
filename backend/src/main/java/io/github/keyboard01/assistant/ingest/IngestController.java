package io.github.keyboard01.assistant.ingest;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.AppUser;
import io.github.keyboard01.assistant.security.CurrentUser;
import io.github.keyboard01.assistant.security.RequireRole;

@RestController
@RequestMapping("/api")
public class IngestController {

    /**
     * 文档列表的最小字段视图：`source_path`（服务器内网路径）、`content_hash` 与内核内部 id
     * 属于内部信息，viewer 也没必要看到。列表页只需要编号、标题、密级与索引状态。
     */
    public record DocumentView(long id, String domainCode, String docNo, String title, String version,
                               String docStatus, String secrecy, String ownerDept, String mimeType,
                               Long fileSize, String chunkStatus, Integer chunkCount,
                               String lastError, String uploadedBy,
                               String kind, String assetStatus, Integer assetCount) {

        static DocumentView of(DocumentRepository.Row row) {
            return new DocumentView(row.id(), row.domainCode(), row.docNo(), row.title(), row.version(),
                    row.docStatus(), row.secrecy(), row.ownerDept(), row.mimeType(), row.fileSize(),
                    row.chunkStatus(), row.chunkCount(),
                    row.lastError(), row.uploadedBy(),
                    row.kind(), row.assetStatus(), row.assetCount());
        }
    }

    private final IngestService service;
    private final DocumentRepository documents;
    private final IngestTaskRepository tasks;
    private final io.github.keyboard01.assistant.domain.DomainService domains;

    public IngestController(IngestService service, DocumentRepository documents, IngestTaskRepository tasks,
                            io.github.keyboard01.assistant.domain.DomainService domains) {
        this.service = service;
        this.documents = documents;
        this.tasks = tasks;
        this.domains = domains;
    }

    /**
     * 文档列表：受域级 + 文档级 ACL 限制。非管理员再收窄为"现行 + 自己上传的草案"，
     * 管理员看全部（含待审草案，否则无法审核）。
     */
    @GetMapping("/documents")
    public Map<String, Object> documents() {
        AppUser user = CurrentUser.require();
        AccessFilter filter = AccessFilter.of(user.subject(), AccessFilter.DEFAULT_ALIAS);
        return Map.of("documents", documents.findVisible(filter, user.username(), user.hasRole("admin"))
                .stream().map(DocumentView::of).toList());
    }

    @PostMapping("/ingest/scan")
    @RequireRole("admin")
    public IngestService.ScanSummary scan() {
        return service.scan();
    }

    /** 重建自建片段（解析器/分块规则改了以后用）。force=true 时连内容没变的也重算。 */
    @PostMapping("/ingest/reindex")
    @RequireRole("admin")
    public IngestService.ReindexSummary reindex(@RequestParam(defaultValue = "false") boolean force) {
        return service.reindex(force);
    }

    public record ParsePreviewRequest(long documentId) {
    }

    /** 解析预览（P1）：把某份文档解析出的块列出来人工确认；与入库走同一条解析路径。 */
    @PostMapping("/ingest/parse-preview")
    @RequireRole("admin")
    public IngestService.ParsePreview parsePreview(@RequestBody ParsePreviewRequest request) {
        return service.preview(request.documentId());
    }

    /**
     * 浏览器上传：写盘 + 登记 manifest + 入内核 + 解析 + 按密级写 ACL。
     * 登录即可（不再限 admin），但要过域级门；普通用户一律落"草案·未生效 + 内部公开"等审核。
     */
    @PostMapping(value = "/ingest/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestService.FileResult upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String domainCode,
            @RequestParam String docNo,
            @RequestParam String title,
            @RequestParam String version,
            @RequestParam(required = false) String docStatus,
            @RequestParam(required = false) String secrecy,
            @RequestParam(required = false) String ownerDept,
            @RequestParam(required = false) String effectiveFrom,
            @RequestParam(required = false) String effectiveTo) throws java.io.IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        AppUser user = CurrentUser.require();
        IngestService.UploadRequest request = new IngestService.UploadRequest(domainCode, docNo, title, version,
                docStatus, secrecy, ownerDept, effectiveFrom, effectiveTo,
                file.getOriginalFilename(), file.getBytes());
        return service.upload(request, user.subject(), user.hasRole("admin"));
    }

    public record StatusRequest(String docStatus) {
    }

    private static final java.util.Set<String> DOC_STATUSES = java.util.Set.of("现行", "已废止", "草案·未生效");

    /**
     * 文档版本状态切换（现行 / 已废止 / 草案·未生效）。
     * 多项目接入 S3 起放开给租户管理员：只能审核**自己租户**的文档（按文档所属域的租户判）。
     */
    @PutMapping("/documents/{id}/status")
    public DocumentView updateStatus(@PathVariable long id, @RequestBody StatusRequest request) {
        String status = request.docStatus() == null ? "" : request.docStatus().trim();
        if (!DOC_STATUSES.contains(status)) {
            throw new IllegalArgumentException("文档状态只能是：" + String.join(" / ", DOC_STATUSES));
        }
        DocumentRepository.Row row = documents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在：" + id));
        domains.requireAdministerDomain(CurrentUser.subject(), row.domainCode(), "审核文档 " + id);
        documents.updateStatus(id, status);
        return documents.findById(id).map(DocumentView::of)
                .orElseThrow(() -> new IllegalStateException("更新后读取文档失败：" + id));
    }

    @GetMapping("/ingest/tasks")
    @RequireRole("admin")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "50") int limit) {
        return Map.of("tasks", tasks.recent(Math.min(Math.max(limit, 1), 500)));
    }

    @PostMapping("/ingest/tasks/{id}/retry")
    @RequireRole("admin")
    public IngestService.FileResult retry(@PathVariable long id) {
        return service.retry(id);
    }

    @PostMapping("/ingest/retry-failed")
    @RequireRole("admin")
    public Map<String, Object> retryFailed() {
        List<IngestService.FileResult> results = service.retryFailed();
        boolean allOk = results.stream().noneMatch(result -> "FAILED".equals(result.status()));
        return Map.of("ok", allOk, "results", results);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
}
