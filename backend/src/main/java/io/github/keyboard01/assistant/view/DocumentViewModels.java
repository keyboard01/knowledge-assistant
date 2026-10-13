package io.github.keyboard01.assistant.view;

import java.util.List;
import java.util.Map;

/** 文档查看（T19~T21）的对外模型。 */
public final class DocumentViewModels {

    private DocumentViewModels() {
    }

    /**
     * 查看入口。三个入口各自留审计——分开记是因为它们的后果不同：
     * 预览只是看了，DOWNLOAD 是文件真的离开了系统。
     */
    public enum Channel {
        FILE("原文预览"),
        CONTENT("Markdown 视图"),
        DOWNLOAD("下载"),
        IMAGE("图片查看");

        private final String label;

        Channel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一个结构化块：Markdown、重排 PDF 与界面共用同一份切分结果，避免几处各排一遍。
     *
     * <p>{@code sectionHead} = "这个块之前要补一行章节标题"。DOCX 的标题被解析器折进了
     * {@code section_path}，没有单独成块，所以"何时该出标题"只能由 section_path 的跳变推出来。
     * 这条规则原来写在 MarkdownBuilder 里，现在提上来由服务层算一次——三个消费方读同一个布尔值，
     * 不各推一遍，就不会出现"Markdown 有标题、PDF 没有"这种同一份文档两种章节结构。</p>
     */
    public record Block(long chunkId, String kind, int level, String text,
                        Integer pageFrom, Integer pageTo, String sectionPath, boolean sectionHead) {

        /** 不带章节标记的构造：默认"此处不出标题"，供按文档顺序直读片段的场景使用。 */
        public Block(long chunkId, String kind, int level, String text,
                     Integer pageFrom, Integer pageTo, String sectionPath) {
            this(chunkId, kind, level, text, pageFrom, pageTo, sectionPath, false);
        }
    }

    /** Markdown 视图的响应体。 */
    public record ContentView(long documentId, String domainCode, String docNo, String title, String version,
                              String docStatus, String secrecy, String ownerDept, String mimeType,
                              boolean originalPdf, String watermarkText, String markdown,
                              List<Block> blocks, Map<String, Integer> pageOfChunk, int chunkCount) {
    }
}
