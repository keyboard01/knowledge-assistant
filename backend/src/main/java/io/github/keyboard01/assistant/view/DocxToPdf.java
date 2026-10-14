package io.github.keyboard01.assistant.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import io.github.keyboard01.assistant.view.DocumentViewModels.Block;

/**
 * 把解析出来的块重新排版成 PDF（T21）。
 *
 * <p><b>为什么是"重排"而不是"转换"。</b>本机没有 LibreOffice，也没装 Word，
 * Flying Saucer 的构件不在离线仓库里——没有一条能"保真转换"的路。所以这里退一步：
 * 用 {@code document_chunk} 里的块（与 Markdown 视图同一份数据）重新排出一份**可读**的 PDF。
 * 版式必然与 Word 原样不同（这是已知限制，登记在 §13），但正文、标题层级、表格行与列表项都在，
 * 而"能看到内容"正是这次功能要解决的事。</p>
 *
 * <p><b>为什么用嵌入字体。</b>重排要画中文，必须嵌入 TrueType；{@link PDType0Font} 支持子集化嵌入。
 * 字体从系统目录里找，只找 .ttf——.ttc 是字体集合，需要 TrueTypeCollection 另走一套加载。
 * 找不到时退回内置 Helvetica 并丢弃非 ASCII 字符：中文会缺，但接口不崩，日志里有 warn 可查。</p>
 */
public final class DocxToPdf {

    private static final float MARGIN = 56f;
    private static final float BODY_SIZE = 10.5f;
    private static final float TABLE_SIZE = 9.5f;
    private static final float LINE_GAP = 1.6f;
    private static final float TABLE_INDENT = 14f;

    private static final String[] FONT_FILES = {
            "simhei.ttf", "simkai.ttf", "simfang.ttf", "Deng.ttf", "NotoSansSC-VF.ttf"
    };

    private static volatile byte[] cachedFont;
    private static volatile boolean fontSearched;

    private DocxToPdf() {
    }

    /**
     * 要画进重排 PDF 的一张图片（sidecar：不进 chunk 正文，只在渲染时按页码插回）。
     * {@code page} 是幻灯片号（与块的 pageFrom 同一坐标系）；DOCX 没有页码语义，给 null 排到文档末尾。
     */
    public record SlideImage(Integer page, byte[] bytes, String ext, int width, int height) {
    }

    public static byte[] render(String title, String docNo, String version, List<Block> blocks)
            throws IOException {
        return render(title, docNo, version, blocks, List.of());
    }

    public static byte[] render(String title, String docNo, String version, List<Block> blocks,
                                List<SlideImage> images)
            throws IOException {
        try (PDDocument document = new PDDocument()) {
            byte[] fontBytes = fontBytes();
            PDFont font;
            boolean cjk = fontBytes != null;
            if (cjk) {
                font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes), true);
            } else {
                font = PDType1Font.HELVETICA;
            }
            Cursor cursor = new Cursor(document, font, cjk);

            String heading = title == null || title.isBlank() ? "未命名文档" : title.trim();
            cursor.write(heading, 16f, 1.7f, 0, 0, 0);

            StringBuilder meta = new StringBuilder();
            if (docNo != null && !docNo.isBlank()) {
                meta.append(docNo.trim());
            }
            if (version != null && !version.isBlank()) {
                if (meta.length() > 0) {
                    meta.append("  ");
                }
                meta.append(version.trim());
            }
            if (meta.length() > 0) {
                cursor.write(meta.toString(), 9f, 1.6f, 107, 115, 128);
            }
            cursor.gap(8f);

            for (Object item : interleave(blocks, images)) {
                if (item instanceof SlideImage image) {
                    drawImage(cursor, document, image);
                    continue;
                }
                Block block = (Block) item;
                String text = block.text();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String kind = block.kind() == null ? "" : block.kind().toUpperCase(Locale.ROOT);
                if (block.sectionHead()) {
                    // 与 Markdown 视图同一份章节规则：DOCX 的标题折在 section_path 里，
                    // 不补这一行，重排出来的 PDF 就会是一堆没有小标题的段落
                    cursor.gap(7f);
                    cursor.write(block.sectionPath().trim(), 13f, 1.5f, 0, 0, 0);
                }
                switch (kind) {
                    case "HEADING" -> {
                        cursor.gap(6f);
                        cursor.write(text.trim(), headingSize(block.level()), 1.55f, 0, 0, 0);
                    }
                    case "BULLET" -> cursor.write("- " + text.trim(), BODY_SIZE, LINE_GAP,
                            26, 26, 31, TABLE_INDENT);
                    case "TABLE_ROW" -> cursor.write(text.trim(), TABLE_SIZE, LINE_GAP,
                            51, 56, 71, TABLE_INDENT);
                    default -> cursor.write(text.trim(), BODY_SIZE, LINE_GAP, 18, 18, 23);
                }
            }
            cursor.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 块与图片的交错顺序：某页的图片插在"页码比它大的第一个文本块"之前——
     * 即跟在该幻灯片文本之后、下一张幻灯片之前。纯图片幻灯片（没有文本块）的图片
     * 会在下一个更大页码的块之前被冲出来，位置不丢。没有页码的图片（DOCX）排到末尾。
     * 纯函数，供单测直接断言顺序。
     */
    static List<Object> interleave(List<Block> blocks, List<SlideImage> images) {
        List<SlideImage> sorted = new ArrayList<>(images);
        sorted.sort(Comparator.comparing(DocxToPdf::effectivePage));
        List<Object> merged = new ArrayList<>(blocks.size() + sorted.size());
        int next = 0;
        for (Block block : blocks) {
            Integer page = block.pageFrom();
            if (page != null) {
                while (next < sorted.size() && effectivePage(sorted.get(next)) < page) {
                    merged.add(sorted.get(next++));
                }
            }
            merged.add(block);
        }
        while (next < sorted.size()) {
            merged.add(sorted.get(next++));
        }
        return merged;
    }

    private static int effectivePage(SlideImage image) {
        return image.page() == null ? Integer.MAX_VALUE : image.page();
    }

    /** 画一张图：宽度铺满内容区等比缩放，超高再压到可用页高的 90%；放不下就换页。单张失败跳过，不挡整份 PDF。 */
    private static void drawImage(Cursor cursor, PDDocument document, SlideImage image) {
        try {
            PDImageXObject xobject = toXObject(document, image);
            if (xobject == null) {
                return;
            }
            int sourceWidth = image.width() > 0 ? image.width() : xobject.getWidth();
            int sourceHeight = image.height() > 0 ? image.height() : xobject.getHeight();
            float width = cursor.contentWidth();
            float height = sourceWidth > 0 ? width * sourceHeight / sourceWidth : width;
            float maxHeight = cursor.usableHeight() * 0.9f;
            if (height > maxHeight) {
                height = maxHeight;
                width = sourceHeight > 0 ? height * sourceWidth / sourceHeight : height;
            }
            cursor.image(xobject, width, height);
        } catch (IOException | RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(DocxToPdf.class)
                    .warn("[重排] 图片（页 {}，{} 字节）画不进去，已跳过：{}",
                            image.page(), image.bytes() == null ? 0 : image.bytes().length, ex.getMessage());
        }
    }

    /** JPEG 直走 DCT 不膨胀；其余光栅格式经 ImageIO 走无损嵌入，读不出来返回 null。 */
    private static PDImageXObject toXObject(PDDocument document, SlideImage image) throws IOException {
        String ext = image.ext() == null ? "" : image.ext().toLowerCase(Locale.ROOT);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            try {
                return JPEGFactory.createFromByteArray(document, image.bytes());
            } catch (IOException ex) {
                // 扩展名说是 JPEG 但字节不是（降采样失败回退原字节时会出现），落到通用路径再试一次
            }
        }
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(image.bytes()));
        if (buffered == null) {
            org.slf4j.LoggerFactory.getLogger(DocxToPdf.class)
                    .warn("[重排] 无法解码图片（页 {}，ext={}），跳过", image.page(), image.ext());
            return null;
        }
        return LosslessFactory.createFromImage(document, buffered);
    }

    private static float headingSize(int level) {
        return switch (Math.max(1, Math.min(level, 5))) {
            case 1 -> 14.5f;
            case 2 -> 12.5f;
            case 3 -> 11.5f;
            default -> 11f;
        };
    }

    /** 系统字体只读一次；找不到就停在 null，不做反复的磁盘探测。 */
    private static byte[] fontBytes() {
        if (cachedFont != null || fontSearched) {
            return cachedFont;
        }
        synchronized (DocxToPdf.class) {
            if (cachedFont != null || fontSearched) {
                return cachedFont;
            }
            fontSearched = true;
            for (Path candidate : fontCandidates()) {
                if (Files.isRegularFile(candidate)) {
                    try {
                        cachedFont = Files.readAllBytes(candidate);
                        return cachedFont;
                    } catch (IOException ignored) {
                        // 换下一个候选
                    }
                }
            }
            org.slf4j.LoggerFactory.getLogger(DocxToPdf.class)
                    .warn("未找到可用的中文字体，DOCX 转 PDF 退回内置字体（中文会缺字）");
            return null;
        }
    }

    private static List<Path> fontCandidates() {
        String windir = System.getenv("WINDIR");
        String[] roots = {
                (windir == null || windir.isBlank() ? "C:\\Windows" : windir) + "\\Fonts",
                "C:\\Windows\\Fonts",
                "/usr/share/fonts",
                "/usr/share/fonts/truetype",
                "/usr/local/share/fonts"
        };
        List<Path> candidates = new ArrayList<>();
        for (String root : roots) {
            for (String file : FONT_FILES) {
                candidates.add(Path.of(root, file));
            }
        }
        return candidates;
    }

    /** "写到哪了"的游标：负责换页、折行与坐标。 */
    private static final class Cursor {

        private final PDDocument document;
        private final PDFont font;
        private final boolean cjk;
        private final float pageWidth;
        private final float pageHeight;
        private PDPageContentStream content;
        private float y;

        Cursor(PDDocument document, PDFont font, boolean cjk) throws IOException {
            this.document = document;
            this.font = font;
            this.cjk = cjk;
            this.pageWidth = PDRectangle.A4.getWidth();
            this.pageHeight = PDRectangle.A4.getHeight();
            newPage();
        }

        private void newPage() throws IOException {
            if (content != null) {
                content.close();
            }
            // 页必须先挂进页面树：PDPageContentStream 只负责写流，不会替你把页收进 document，
            // 少了 addPage 就会得到一份 0 页的 PDF（保存成功、内容全丢，最难查的那种）
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = pageHeight - MARGIN;
        }

        void gap(float size) {
            y -= size;
        }

        float contentWidth() {
            return pageWidth - MARGIN * 2;
        }

        float usableHeight() {
            return pageHeight - MARGIN * 2;
        }

        /** 剩余高度放不下就换页；画完把游标下移（图底下留一点呼吸）。 */
        void image(PDImageXObject xobject, float width, float height) throws IOException {
            if (y - height < MARGIN) {
                newPage();
            }
            content.drawImage(xobject, MARGIN, y - height, width, height);
            y -= height + 6f;
        }

        void write(String text, float size, float gap, int red, int green, int blue)
                throws IOException {
            write(text, size, gap, red, green, blue, 0f);
        }

        void write(String text, float size, float gap, int red, int green, int blue, float indent)
                throws IOException {
            float lineHeight = size * gap;
            for (String line : wrap(sanitize(text), size)) {
                if (y - lineHeight < MARGIN) {
                    newPage();
                }
                content.beginText();
                content.setNonStrokingColor(red, green, blue);
                content.setFont(font, size);
                content.newLineAtOffset(MARGIN + indent, y - size);
                content.showText(encodable(line));
                content.endText();
                y -= lineHeight;
            }
        }

        /**
         * 去掉当前字体没有字形的字符。PDFBox 的 {@code showText} 遇到没字形的字会**直接抛异常**，
         * 结果是整份「原文」打不开——一个 U+FFFD（上游编码出错留下的替换符）或一个生僻字就够了。
         * 丢一个字符与整页看不到，代价完全不对等，所以这里与"没有中文字体时丢非 ASCII"取同一条取向：
         * 能显示多少显示多少。
         */
        private String encodable(String line) {
            if (line == null || line.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder(line.length());
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                try {
                    font.getStringWidth(String.valueOf(c));
                    builder.append(c);
                } catch (IOException | IllegalArgumentException ex) {
                    // 字体没有这个字形：跳过
                }
            }
            return builder.toString();
        }

        void finish() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }

        /**
         * 逐字折行：中文没有词边界，按"字"累加宽度最稳；拉丁文里优先在空格处断开，
         * 避免把一个单词劈成两半。宽度比较的口径必须与 draw 时一致——都乘字号。
         */
        private List<String> wrap(String text, float size) {
            List<String> lines = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                lines.add("");
                return lines;
            }
            float maxWidth = pageWidth - MARGIN * 2;
            StringBuilder current = new StringBuilder();
            float currentWidth = 0f;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lines.add(current.toString());
                    current.setLength(0);
                    currentWidth = 0f;
                    continue;
                }
                float charWidth = charWidth(c, size);
                if (currentWidth + charWidth > maxWidth && current.length() > 0) {
                    int breakAt = lastSpace(current);
                    if (breakAt > 0 && breakAt < current.length() - 1) {
                        lines.add(current.substring(0, breakAt));
                        String rest = current.substring(breakAt + 1);
                        current.setLength(0);
                        current.append(rest);
                        currentWidth = widthOf(rest, size);
                    } else {
                        lines.add(current.toString());
                        current.setLength(0);
                        currentWidth = 0f;
                    }
                }
                current.append(c);
                currentWidth += charWidth;
            }
            lines.add(current.toString());
            return lines;
        }

        private float charWidth(char c, float size) {
            try {
                return font.getStringWidth(String.valueOf(c)) / 1000f * size;
            } catch (IOException | IllegalArgumentException ex) {
                // 字体缺字：按 0 宽处理，字符会在 showText 前被 sanitize 之外的场景漏掉，
                // 这里只需保证不因为一个生僻字把整篇排版算崩
                return 0f;
            }
        }

        private float widthOf(String value, float size) {
            float total = 0f;
            for (int i = 0; i < value.length(); i++) {
                total += charWidth(value.charAt(i), size);
            }
            return total;
        }

        private static int lastSpace(StringBuilder builder) {
            for (int i = builder.length() - 1; i >= 0; i--) {
                if (builder.charAt(i) == ' ') {
                    return i;
                }
            }
            return -1;
        }

        /** 退回内置字体时只保留 ASCII：否则 showText 遇到中文会抛异常，整个文档打不开。 */
        private String sanitize(String text) {
            if (cjk || text == null) {
                return text;
            }
            StringBuilder builder = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (c >= 0x20 && c <= 0x7E) {
                    builder.append(c);
                }
            }
            return builder.toString();
        }
    }
}
