package io.github.keyboard01.assistant.view;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

/**
 * 给 PDF 烧水印（T21）。
 *
 * <p>为什么是"烧进字节"而不是界面叠加：D5 允许下载源文件。只要下载件是原始字节，
 * 界面上的水印就是摆设——把文件存下来就绕过了。所以水印必须在这一层完成。</p>
 *
 * <p>为什么用内置的 Helvetica 而不用中文字体：水印文字是"账号 + 空格 + yyyy-MM-dd"，
 * 账号是 ASCII 的。用标准字体意味着**水印不依赖任何字体文件**，
 * 少一个"部署到新机器就画不出水印"的失败模式。非 ASCII 字符会被丢弃（见 {@link #asciiToken}）。</p>
 */
public final class PdfWatermarker {

    private static final float GRID_X = 300f;
    private static final float GRID_Y = 170f;
    private static final float FONT_SIZE = 15f;
    private static final float ALPHA = 0.13f;

    private PdfWatermarker() {
    }

    /** 在每一页铺满 45° 半透明重复文字，返回新的字节（不改调用方手里的数组）。 */
    public static byte[] stamp(byte[] pdf, String watermarkText) throws IOException {
        String text = asciiToken(watermarkText);
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFont font = PDType1Font.HELVETICA_BOLD;
            for (PDPage page : document.getPages()) {
                PDRectangle box = page.getMediaBox();
                // resetContext=true：页面带 /Rotate 时也要在视觉坐标系里铺，否则水印会歪着或者跑到页面外
                try (PDPageContentStream content = new PDPageContentStream(
                        document, page, AppendMode.APPEND, true, true)) {
                    PDExtendedGraphicsState state = new PDExtendedGraphicsState();
                    state.setNonStrokingAlphaConstant(ALPHA);
                    content.setGraphicsStateParameters(state);
                    content.setNonStrokingColor(110, 110, 110);
                    content.setFont(font, FONT_SIZE);
                    float width = box.getWidth();
                    float height = box.getHeight();
                    for (float y = -height; y < height * 2; y += GRID_Y) {
                        for (float x = -width; x < width * 2; x += GRID_X) {
                            content.saveGraphicsState();
                            content.transform(Matrix.getRotateInstance(Math.toRadians(45), x, y));
                            content.beginText();
                            content.newLineAtOffset(0, 0);
                            content.showText(text);
                            content.endText();
                            content.restoreGraphicsState();
                        }
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 只保留可打印 ASCII。
     *
     * <p>标准 14 字体只懂 WinAnsi，遇到中文会直接抛 IllegalArgumentException——
     * 那时用户看到的不是"水印少几个字"，而是"整个文档打不开"。账号本来就限定 ASCII，
     * 这里是给"万一有人建了中文账号"留的兜底。</p>
     */
    static String asciiToken(String value) {
        StringBuilder builder = new StringBuilder();
        for (char c : (value == null ? "" : value).toCharArray()) {
            if (c >= 0x20 && c <= 0x7E) {
                builder.append(c);
            }
        }
        String cleaned = builder.toString().trim();
        return cleaned.isEmpty() ? "user" : cleaned;
    }
}
