package io.github.keyboard01.assistant.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.keyboard01.assistant.view.DocumentViewModels.Block;

/**
 * 重排 PDF 的图片混排（sidecar 落地）。两个判据：
 *
 * <ul>
 *   <li><b>顺序</b>：图片必须跟在所属幻灯片的文本之后、下一张幻灯片之前（{@code interleave} 是纯函数，直接断言）；</li>
 *   <li><b>冒烟</b>：真实渲染出能被 PDFBox 打开的 PDF——画图代码坏起来不报错，只会得到 0 页或打不开的文件。</li>
 * </ul>
 */
class DocxToPdfTest {

    private static final List<Block> BLOCKS = List.of(
            new Block(1L, "HEADING", 1, "封面", 1, 1, "封面"),
            new Block(2L, "PARAGRAPH", 1, "第一页正文。", 1, 1, "封面"),
            new Block(3L, "HEADING", 1, "架构", 2, 2, "架构"),
            new Block(4L, "BULLET", 1, "分层", 2, 2, "架构"),
            new Block(5L, "HEADING", 1, "总结", 4, 4, "总结"));

    private static Block block(String text) {
        return BLOCKS.stream().filter(b -> b.text().equals(text)).findFirst().orElseThrow();
    }

    private static List<String> kinds(List<Object> merged) {
        List<String> out = new ArrayList<>();
        for (Object item : merged) {
            out.add(item instanceof DocxToPdf.SlideImage image
                    ? "IMG" + image.page() : "TXT" + ((Block) item).text());
        }
        return out;
    }

    @Test
    @DisplayName("交错：某页的图跟在该页文本之后、下一页文本之前；纯图片页的图不丢")
    void interleavePutsImageAfterItsSlide() {
        List<DocxToPdf.SlideImage> images = List.of(
                new DocxToPdf.SlideImage(2, new byte[1], "png", 10, 10),
                new DocxToPdf.SlideImage(1, new byte[1], "png", 10, 10),
                new DocxToPdf.SlideImage(3, new byte[1], "png", 10, 10)); // 幻灯片 3 没有文本块
        List<String> merged = kinds(DocxToPdf.interleave(BLOCKS, images));
        // 页 1 的图在"第一页正文"之后、"架构"（页 2）之前
        assertTrue(merged.indexOf("IMG1") > merged.indexOf("TXT第一页正文。"), merged.toString());
        assertTrue(merged.indexOf("IMG1") < merged.indexOf("TXT架构"), merged.toString());
        // 页 2 的图在"分层"（页 2 最后一块）之后；页 3 的图（纯图片页）在"总结"（页 4）之前
        assertTrue(merged.indexOf("IMG2") > merged.indexOf("TXT分层"), merged.toString());
        assertTrue(merged.indexOf("IMG3") < merged.indexOf("TXT总结"), merged.toString());
    }

    @Test
    @DisplayName("交错：没有页码的图（DOCX）排到文档末尾")
    void interleavePutsPagelessImagesLast() {
        List<DocxToPdf.SlideImage> images = List.of(
                new DocxToPdf.SlideImage(null, new byte[1], "png", 10, 10),
                new DocxToPdf.SlideImage(1, new byte[1], "png", 10, 10));
        List<Object> merged = DocxToPdf.interleave(BLOCKS, images);
        Object last = merged.get(merged.size() - 1);
        assertEquals(DocxToPdf.SlideImage.class, last.getClass());
        assertEquals(null, ((DocxToPdf.SlideImage) last).page());
    }

    @Test
    @DisplayName("交错：没有图片时输出与输入块完全一致（纯文本路径零改动）")
    void interleaveWithoutImagesIsIdentity() {
        assertEquals(BLOCKS, DocxToPdf.interleave(BLOCKS, List.of()));
    }

    @Test
    @DisplayName("渲染冒烟：带图的块能渲染出可打开、非 0 页的 PDF，正文不丢")
    void rendersPdfWithImages() throws IOException {
        byte[] png = pngBytes(20, 12);
        List<DocxToPdf.SlideImage> images = List.of(
                new DocxToPdf.SlideImage(1, png, "png", 20, 12),
                new DocxToPdf.SlideImage(2, png, "png", 20, 12));
        byte[] pdf = DocxToPdf.render("带图文档", "QM-T-003", "V1.0", BLOCKS, images);
        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() > 0, "渲染结果不能是 0 页");
        }
        // 空图片列表：等价于旧签名
        byte[] plain = DocxToPdf.render("带图文档", "QM-T-003", "V1.0", BLOCKS, List.of());
        try (PDDocument document = PDDocument.load(plain)) {
            assertTrue(document.getNumberOfPages() > 0);
        }
    }

    @Test
    @DisplayName("渲染冒烟：解码不了的图片字节被跳过，不挡整份 PDF")
    void skipsUndecodableImage() throws IOException {
        List<DocxToPdf.SlideImage> images = List.of(
                new DocxToPdf.SlideImage(1, new byte[] {1, 2, 3, 4}, "png", 10, 10));
        byte[] pdf = DocxToPdf.render("坏图文档", "QM-T-004", "V1.0", BLOCKS, images);
        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() > 0, "坏图不应挡住整份渲染");
        }
    }

    @Test
    @DisplayName("渲染冒烟：字体没有字形的字符被跳过，不挡整份 PDF")
    void skipsUnrenderableGlyphs() throws IOException {
        // U+FFFD 是上游编码出错留下的替换符（真实上传里出现过）；🎉 是字体里根本没有的字形。
        // PDFBox 的 showText 遇到这类字符会直接抛异常，那会让整份「原文」打不开。
        List<Block> blocks = List.of(
                new Block(1L, "HEADING", 1, "\uFFFD\uFFFD表格验证 \uD83C\uDF89", 1, 1, null),
                new Block(2L, "PARAGRAPH", 1, "正常一行 U+FFFD\uFFFD 混着一个坏字符。", 1, 1, null));
        byte[] pdf = DocxToPdf.render("带坏字符的文档 \uFFFD", "QM-T-005", "V1.0", blocks);
        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() > 0, "坏字符不该让整份渲染失败");
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            assertTrue(text.contains("正常一行"), "好字符要照常渲染：" + text);
        }
    }

    /** 生成一张纯色 PNG：ImageIO 全链路可用，不依赖测试资源文件。 */
    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(30, 90, 200));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
