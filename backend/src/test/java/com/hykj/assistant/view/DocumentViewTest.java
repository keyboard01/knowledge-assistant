package com.hykj.assistant.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hykj.assistant.view.DocumentViewModels.Block;

/**
 * 文档查看里"最容易悄悄坏掉"的两块：Markdown 拼装与 PDF 盖章。
 *
 * <p>它们坏起来都不报错：Markdown 会少一个标题，PDF 会变成 0 页或者丢水印。
 * 所以这里用 PDFBox 把文字**提取出来**断言（AC-D-008 的判据也是提取文本，不是看截图），
 * 而不是断言"字节数大于 0"。</p>
 */
class DocumentViewTest {

    private static final List<Block> BLOCKS = List.of(
            new Block(1L, "PARAGRAPH", 1, "本手册说明液压站的原理。", 1, 1, "1 概述"),
            new Block(2L, "HEADING", 1, "2 主要元件", 2, 2, "2 主要元件"),
            new Block(3L, "PARAGRAPH", 1, "液压泵是动力元件。", 2, 2, "2 主要元件"),
            new Block(4L, "BULLET", 1, "齿轮泵", 2, 2, "2 主要元件"),
            new Block(5L, "TABLE_ROW", 1, "型号 ｜ 流量 —— 压力", 3, 3, "3 参数"));

    @Test
    @DisplayName("Markdown：章节路径跳变补标题，列表与表格行原样成行")
    void markdownKeepsSections() {
        String title = "液压站 HYZ-40 原理与维护说明";
        String markdown = MarkdownBuilder.build(title, DocumentViewService.withSectionHeads(BLOCKS, title));
        assertTrue(markdown.startsWith("# 液压站 HYZ-40 原理与维护说明"), markdown);
        assertTrue(markdown.contains("## 1 概述"), markdown);
        assertTrue(markdown.contains("## 2 主要元件"), markdown);
        assertTrue(markdown.contains("## 3 参数"), markdown);
        assertTrue(markdown.contains("- 齿轮泵"), markdown);
        // 表格行原样成行：不猜结构，宁可让用户看到符号
        assertTrue(markdown.contains("型号 ｜ 流量 —— 压力"), markdown);
    }

    @Test
    @DisplayName("Markdown：与文档标题同名的小节不再补标题")
    void markdownSkipsDocTitleSection() {
        String title = "抬头与目录";
        List<Block> blocks = DocumentViewService.withSectionHeads(
                List.of(new Block(1L, "PARAGRAPH", 1, "正文", 1, 1, "抬头与目录")), title);
        String markdown = MarkdownBuilder.build(title, blocks);
        assertFalse(markdown.contains("## 抬头与目录"), markdown);
    }

    @Test
    @DisplayName("章节规则只有一份：重排 PDF 也会补出与 Markdown 相同的小标题")
    void pdfAlsoGetsSectionHeadings() throws IOException {
        String title = "液压站 HYZ-40 原理与维护说明";
        List<Block> blocks = DocumentViewService.withSectionHeads(BLOCKS, title);
        // Markdown 与 PDF 用同一份带标记的块，两边的小标题必然一致
        assertTrue(MarkdownBuilder.build(title, blocks).contains("## 3 参数"));
        try (PDDocument document = PDDocument.load(
                DocxToPdf.render(title, "QM-T-001", "V1.0", blocks))) {
            String text = flatten(new PDFTextStripper().getText(document));
            assertTrue(text.contains("3参数"), text);
            assertTrue(text.contains("液压泵是动力元件"), text);
        }
    }

    @Test
    @DisplayName("水印：只保留 ASCII；全是非 ASCII 时退化成兜底值")
    void watermarkTokenIsAscii() {
        assertEquals("admin 2026-10-08", PdfWatermarker.asciiToken("admin 2026-10-08"));
        // 中文被丢掉，剩下的日期还在：比整串丢掉更能说明"谁在什么时候拿的"
        assertEquals("2026-10-08", PdfWatermarker.asciiToken("张三 2026-10-08"));
        assertEquals("user", PdfWatermarker.asciiToken("张三"));
    }

    @Test
    @DisplayName("PDF 盖章：提取文本能看到水印，且不是 0 页")
    void watermarkIsExtractable() throws IOException {
        byte[] plain = DocxToPdf.render("测试文档", "QM-T-001", "V1.0", BLOCKS);
        try (PDDocument document = PDDocument.load(plain)) {
            assertTrue(document.getNumberOfPages() > 0, "重排结果不能是 0 页");
        }
        byte[] stamped = PdfWatermarker.stamp(plain, "admin 2026-10-08");
        try (PDDocument document = PDDocument.load(stamped)) {
            String text = new PDFTextStripper().getText(document);
            // 水印是 45° 旋转的，提取出来的文本会被插入换行（甚至一个字一行），
            // 所以判据必须先去空白再比对——AC-D-008 的脚本也用同一口径
            assertTrue(flatten(text).contains("admin2026-10-08"), text);
            // 正文也要在：盖章不能把原内容洗掉
            assertTrue(flatten(text).contains("液压泵是动力元件"), text);
        }
    }

    @Test
    @DisplayName("PDF 重排：长文档跨页，且每一页都带水印")
    void longDocumentSpansPages() throws IOException {
        List<Block> longBlocks = new java.util.ArrayList<>(BLOCKS);
        for (int i = 0; i < 200; i++) {
            longBlocks.add(new Block(100L + i, "PARAGRAPH", 1,
                    "第 " + i + " 段：液压站由动力元件、执行元件、控制元件、辅助元件和液压油五部分组成。",
                    4, 4, "4 组成"));
        }
        byte[] stamped = PdfWatermarker.stamp(DocxToPdf.render("长文档", "QM-T-002", "V2.0", longBlocks),
                "admin 2026-10-08");
        try (PDDocument document = PDDocument.load(stamped)) {
            int pages = document.getNumberOfPages();
            assertTrue(pages > 1, "长文档应当跨页，实际 " + pages + " 页");
            PDFTextStripper stripper = new PDFTextStripper();
            int withWatermark = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (flatten(stripper.getText(document)).contains("admin2026-10-08")) {
                    withWatermark++;
                }
            }
            assertEquals(pages, withWatermark, "每一页都要有水印");
        }
    }

    /** 去掉所有空白：旋转文本的提取结果会被换行切碎，比对前必须归一化。 */
    private static String flatten(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
