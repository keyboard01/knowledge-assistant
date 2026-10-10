package com.hykj.assistant.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hykj.assistant.ingest.DocumentRepository;
import com.hykj.assistant.ingest.IngestService;
import com.hykj.assistant.security.AccessFilter;
import com.hykj.assistant.security.AppUser;
import com.hykj.assistant.security.CurrentUser;
import com.hykj.assistant.security.DocumentAclRepository;
import com.hykj.assistant.view.DocumentViewModels.Block;
import com.hykj.assistant.view.DocumentViewModels.Channel;
import com.hykj.assistant.view.DocumentViewModels.ContentView;

/**
 * 文档查看（T19 / T20 / T21）。
 *
 * <p>三件事绑在一起，缺一件这个功能就不成立：</p>
 * <ol>
 *   <li><b>每次开口都重新判权限。</b>列表里看得到不代表现在还能看——权限行可能刚被改掉。
 *       判定与列表、检索共用 {@link AccessFilter}，绝不在查看这条路径上另写一套规则。</li>
 *   <li><b>水印烧进字节。</b>下载被允许（D5），界面水印就是摆设，所以水印在这一层完成。</li>
 *   <li><b>留审计。</b>拒绝也留一行：那是最该被看见的事件。</li>
 * </ol>
 */
@Service
public class DocumentViewService {

    private static final Logger log = LoggerFactory.getLogger(DocumentViewService.class);

    /** 在线查看/下载的体积上限（AC-D-009）：水印要整份重写，再大就不适合走这条同步路径。 */
    public static final long MAX_PDF_BYTES = 20L * 1024 * 1024;

    private static final int CACHE_ENTRIES = 8;

    private final DocumentRepository documents;
    private final DocumentAclRepository acls;
    private final ViewChunkRepository chunks;
    private final ViewLogRepository viewLog;
    private final IngestService ingest;

    /**
     * DOCX 重排结果的缓存。**只缓存未盖章的字节**——盖章后的成品带着某个账号的水印，
     * 缓存它等于把 A 的水印发给 B。
     */
    private final Map<String, byte[]> convertedCache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            });

    public DocumentViewService(DocumentRepository documents, DocumentAclRepository acls,
                               ViewChunkRepository chunks, ViewLogRepository viewLog, IngestService ingest) {
        this.documents = documents;
        this.acls = acls;
        this.chunks = chunks;
        this.viewLog = viewLog;
        this.ingest = ingest;
    }

    /** 下载/预览用的 PDF 字节与建议文件名。 */
    public record PdfPayload(byte[] bytes, String fileName) {
    }

    public ContentView content(long documentId, String clientIp) {
        Viewer viewer = authorize(documentId, Channel.CONTENT, clientIp);
        DocumentRepository.Row row = viewer.row();
        List<Block> blocks = withSectionHeads(chunks.findByDocument(documentId), row.title());
        Map<String, Integer> pageOfChunk = new LinkedHashMap<>();
        for (Block block : blocks) {
            if (block.pageFrom() != null) {
                pageOfChunk.put(String.valueOf(block.chunkId()), block.pageFrom());
            }
        }
        String markdown = MarkdownBuilder.build(row.title(), blocks);
        return new ContentView(row.id(), row.domainCode(), row.docNo(), row.title(), row.version(),
                row.docStatus(), row.secrecy(), row.ownerDept(), row.mimeType(), isPdf(row),
                viewer.watermark(), markdown, blocks, pageOfChunk, blocks.size());
    }

    public PdfPayload file(long documentId, String clientIp) {
        Viewer viewer = authorize(documentId, Channel.FILE, clientIp);
        return new PdfPayload(pdf(viewer), pdfName(viewer.row()));
    }

    public PdfPayload download(long documentId, String clientIp) {
        Viewer viewer = authorize(documentId, Channel.DOWNLOAD, clientIp);
        return new PdfPayload(pdf(viewer), pdfName(viewer.row()));
    }

    /** 水印文案 = 账号 + 空格 + yyyy-MM-dd。日期取**服务端当天**：改浏览器时间绕不过去。 */
    public static String watermarkOf(String username) {
        return username + " " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private byte[] pdf(Viewer viewer) {
        DocumentRepository.Row row = viewer.row();
        byte[] plain = isPdf(row) ? sourceBytes(row) : converted(row);
        try {
            return PdfWatermarker.stamp(plain, viewer.watermark());
        } catch (IOException ex) {
            throw new IllegalStateException("生成文档水印失败：" + ex.getMessage(), ex);
        }
    }

    /** 原始 PDF 的行内预览与下载都是"源文件字节 + 水印"。 */
    private byte[] sourceBytes(DocumentRepository.Row row) {
        Path file = resolveSource(row);
        try {
            long size = Files.size(file);
            if (size > MAX_PDF_BYTES) {
                throw new DocumentTooLargeException(
                        "文档体积 " + (size / 1024 / 1024) + "MB 超过在线查看上限 20MB，请下载后离线查看");
            }
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalStateException("读取源文件失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * DOCX 先重排成 PDF 再盖章。缓存键带上 contentHash：文件内容变了就是另一次转换，
     * 用旧的排版会把新内容藏起来。
     */
    private byte[] converted(DocumentRepository.Row row) {
        String key = row.id() + ":" + row.contentHash();
        byte[] cached = convertedCache.get(key);
        if (cached != null) {
            return cached;
        }
        Path file = resolveSource(row);
        try {
            long size = Files.size(file);
            if (size > MAX_PDF_BYTES) {
                throw new DocumentTooLargeException(
                        "文档体积 " + (size / 1024 / 1024) + "MB 超过在线查看上限 20MB，请下载后离线查看");
            }
            List<Block> blocks = withSectionHeads(chunks.findByDocument(row.id()), row.title());
            byte[] rendered = DocxToPdf.render(row.title(), row.docNo(), row.version(), blocks);
            convertedCache.put(key, rendered);
            return rendered;
        } catch (IOException ex) {
            throw new IllegalStateException("DOCX 转 PDF 失败：" + ex.getMessage(), ex);
        }
    }

    private Path resolveSource(DocumentRepository.Row row) {
        Path root = ingest.sourceRoot();
        Path file = root.resolve(row.sourcePath()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalStateException("源文件不存在或越出文档根目录：" + row.sourcePath());
        }
        return file;
    }

    /**
     * 标出"哪些块之前要补一行章节标题"。
     *
     * <p>规则只有一条：{@code section_path} 变了就出标题，但两处不出——块本身就是标题（它自己会排版），
     * 以及这个小节名等于文档标题（首块的 section_path 往往就是文档名，再补一遍是噪声）。
     * 放在服务层算一次，是因为 Markdown、重排 PDF 与界面三个消费方都要用同一份答案。</p>
     */
    static List<Block> withSectionHeads(List<Block> blocks, String title) {
        String docTitle = title == null ? "" : title.trim();
        List<Block> marked = new ArrayList<>(blocks.size());
        String current = null;
        for (Block block : blocks) {
            String section = block.sectionPath() == null ? "" : block.sectionPath().trim();
            boolean emit = false;
            if (!section.isEmpty() && !section.equals(current)) {
                current = section;
                emit = !"HEADING".equalsIgnoreCase(block.kind()) && !section.equals(docTitle);
            }
            marked.add(new Block(block.chunkId(), block.kind(), block.level(), block.text(),
                    block.pageFrom(), block.pageTo(), block.sectionPath(), emit));
        }
        return marked;
    }

    private boolean isPdf(DocumentRepository.Row row) {
        return !isDocx(row);
    }

    private static boolean isDocx(DocumentRepository.Row row) {
        String path = row.sourcePath() == null ? "" : row.sourcePath().toLowerCase(java.util.Locale.ROOT);
        String mime = row.mimeType() == null ? "" : row.mimeType().toLowerCase(java.util.Locale.ROOT);
        return path.endsWith(".docx") || mime.contains("wordprocessingml");
    }

    private static String pdfName(DocumentRepository.Row row) {
        List<String> parts = new ArrayList<>();
        parts.add(row.docNo());
        parts.add(row.version());
        String base = String.join("_", parts.stream().filter(part -> part != null && !part.isBlank()).toList());
        // 文件名要进响应头，去掉可能造成头注入的字符
        return (base.isEmpty() ? "document" : base.replaceAll("[\\r\\n\"\\\\/]", "_")) + ".pdf";
    }

    /** 判定 + 留痕。没通过就抛 {@link DocumentAccessDeniedException}，且已经写过一行 DENIED。 */
    private Viewer authorize(long documentId, Channel channel, String clientIp) {
        AppUser user = CurrentUser.require();
        String watermark = watermarkOf(user.username());
        DocumentRepository.Row row = documents.findById(documentId).orElse(null);
        boolean allowed = row != null
                && AccessFilter.of(user.subject(), AccessFilter.DEFAULT_ALIAS)
                        .allows(acls.findByDocumentIds(List.of(documentId)).get(documentId));
        if (!allowed) {
            viewLog.record(row == null ? null : row.id(), row == null ? null : row.docNo(),
                    row == null ? null : row.version(), user.username(), user.roleCode(), channel, "DENIED",
                    null, clientIp);
            if (row == null) {
                log.warn("[查看] 文档 {} 不存在，按无权限同样处理（不暴露存在性）", documentId);
            } else {
                log.warn("[查看] 拒绝 user={} doc={} channel={}", user.username(), row.docNo(), channel);
            }
            throw new DocumentAccessDeniedException();
        }
        viewLog.record(row.id(), row.docNo(), row.version(), user.username(), user.roleCode(), channel, "OK",
                watermark, clientIp);
        return new Viewer(row, watermark);
    }

    private record Viewer(DocumentRepository.Row row, String watermark) {
    }
}
