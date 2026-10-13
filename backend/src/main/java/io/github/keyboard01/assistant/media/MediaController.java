package io.github.keyboard01.assistant.media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.keyboard01.assistant.ingest.IngestService;
import io.github.keyboard01.assistant.security.CurrentUser;
import io.github.keyboard01.assistant.security.RequireRole;
import io.github.keyboard01.assistant.settings.SettingService;
import io.github.keyboard01.assistant.view.DocumentAccessDeniedException;
import io.github.keyboard01.assistant.view.DocumentViewModels.Channel;
import io.github.keyboard01.assistant.view.DocumentViewService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 找图（图片素材检索与查看）。
 *
 * <p>图片的可见性与文档完全一致：检索在 SQL 里下推权限；查看走 {@link DocumentViewService#assertVisible}
 * （域级 + 文档级 ACL + 现行状态 + 审计）。**不另立权限路径。**</p>
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    public record SearchRequest(String domainCode, String query, Integer topN, Double minScore) {
    }

    public record ImageItem(long assetId, long documentId, String docNo, String title, String version, Integer page,
                            String caption, double score, String thumbnailUrl, String rawUrl) {
    }

    private final MediaSearchService searchService;
    private final ImageAssetRepository assets;
    private final AssetStore store;
    private final DocumentViewService documentView;
    private final IngestService ingestService;
    private final SettingService settingService;

    public MediaController(MediaSearchService searchService, ImageAssetRepository assets, AssetStore store,
                           DocumentViewService documentView, IngestService ingestService,
                           SettingService settingService) {
        this.searchService = searchService;
        this.assets = assets;
        this.store = store;
        this.documentView = documentView;
        this.ingestService = ingestService;
        this.settingService = settingService;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) throws IOException, InterruptedException {
        MediaModels.SearchResult result = searchService.search(CurrentUser.subject(), request.domainCode(),
                request.query(), request.topN(), request.minScore(),
                settingService.effective(SettingService.GROUP_IMAGE_EMBEDDING));
        return Map.of("images", result.hits().stream().map(this::toItem).toList(),
                "notice", result.notice() == null ? "" : result.notice());
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long id, HttpServletRequest request) throws IOException {
        return serve(id, true, request);
    }

    @GetMapping("/{id}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable long id, HttpServletRequest request) throws IOException {
        return serve(id, false, request);
    }

    /** 重建图片索引（admin）：只跑图片资产，不动文本 chunk。 */
    @PostMapping("/reindex")
    @RequireRole("admin")
    public IngestService.AssetReindexSummary reindex(@RequestParam(defaultValue = "false") boolean force) {
        return ingestService.reindexImages(force);
    }

    private ResponseEntity<byte[]> serve(long assetId, boolean thumbnail, HttpServletRequest request)
            throws IOException {
        // 资产不存在与无权访问同形：不暴露存在性
        MediaModels.Asset asset = assets.findById(assetId).orElseThrow(DocumentAccessDeniedException::new);
        documentView.assertVisible(asset.documentId(), Channel.IMAGE, clientIp(request));

        Path path = thumbnail
                ? store.thumbPath(asset.contentHash())
                : store.assetPath(asset.contentHash(), asset.ext());
        byte[] bytes;
        try {
            bytes = store.read(path);
        } catch (IOException ex) {
            if (!thumbnail) {
                throw ex;
            }
            // 缩略图缺失（生成失败等）时退回原图
            bytes = store.read(store.assetPath(asset.contentHash(), asset.ext()));
        }
        MediaType type = thumbnail ? MediaType.IMAGE_JPEG : mediaType(asset.ext());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(bytes.length)
                .body(bytes);
    }

    private ImageItem toItem(MediaModels.Hit hit) {
        return new ImageItem(hit.assetId(), hit.documentId(), hit.docNo(), hit.title(), hit.version(), hit.page(),
                hit.caption(), hit.score(), "/api/media/" + hit.assetId() + "/thumbnail",
                "/api/media/" + hit.assetId() + "/raw");
    }

    private static MediaType mediaType(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase(Locale.ROOT)) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            default -> MediaType.IMAGE_JPEG;
        };
    }

    @ExceptionHandler(DocumentAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> onDenied(DocumentAccessDeniedException ex) {
        return Map.of("error", "没有权限查看该图片");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    /** 向量化/内核不可用：与检索一致，返回 503 而不是假装"没找到"。 */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onUnavailable(IOException ex) {
        log.warn("找图失败：向量化或存储不可用", ex);
        return Map.of("error", "图片检索暂时不可用（向量化服务或存储异常），请稍后重试");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
