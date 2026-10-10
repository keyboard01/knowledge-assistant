package com.hykj.assistant.view;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 文档查看三入口（T19）。
 *
 * <p>三个入口是**三条独立的路**，不是同一个东西的三个名字：它们的后果不同（看了 / 看了正文 /
 * 文件真的离开了系统），所以各自单独判权限、单独留审计。想靠"打开过预览就一直放行"是行不通的。</p>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentViewController {

    private final DocumentViewService service;

    public DocumentViewController(DocumentViewService service) {
        this.service = service;
    }

    /** Markdown 视图：返回 JSON，正文由前端渲染。 */
    @GetMapping("/{id}/content")
    public DocumentViewModels.ContentView content(@PathVariable long id, HttpServletRequest request) {
        return service.content(id, clientIp(request));
    }

    /** 原文预览：内联 PDF（PDF 原样盖章，DOCX 重排后盖章）。 */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable long id, HttpServletRequest request) {
        return pdf(service.file(id, clientIp(request)), "inline");
    }

    /**
     * 下载。**统一给 PDF**：DOCX 不再回原始 .docx——那样要么没水印（可绕开），
     * 要么得靠 POI 写页眉（观感与预览不一致）。统一成一份带水印的 PDF，预览与下载看到的是同一份东西。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id, HttpServletRequest request) {
        return pdf(service.download(id, clientIp(request)), "attachment");
    }

    private static ResponseEntity<byte[]> pdf(DocumentViewService.PdfPayload payload, String dispositionType) {
        ContentDisposition disposition = ContentDisposition.builder(dispositionType)
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 内容带登录人水印，任何中间缓存都不该留存—同一份文档不同人拿到的是不同字节
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(payload.bytes().length)
                .body(payload.bytes());
    }

    @ExceptionHandler(DocumentAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> onDenied(DocumentAccessDeniedException ex) {
        // 固定文案：不带标题、编号、路径。403 之外再泄漏一点信息就等于没拦
        return Map.of("error", "没有权限查看该文档");
    }

    @ExceptionHandler(DocumentTooLargeException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, String> onTooLarge(DocumentTooLargeException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> onFailure(IllegalStateException ex) {
        return Map.of("error", ex.getMessage() == null ? "查看失败" : ex.getMessage());
    }

    /** 审计要记来源：X-Forwarded-For 取第一段（最靠近客户端的那个代理写的），否则 socket 地址。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}