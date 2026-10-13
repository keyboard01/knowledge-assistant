package io.github.keyboard01.assistant.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

/**
 * PPTX 解析（POI XSLF）。每张幻灯片算一"页"（`page` = 幻灯片序号，1 起，与 PDF 页码同义）。
 *
 * <p>块类型映射：标题占位符 → `HEADING`；缩进层级 &gt; 0 → `BULLET`；其余 → `PARAGRAPH`；
 * 表格整行拼成 `TABLE_ROW`（与 DOCX 同一种分隔符，便于下游按行切分）。</p>
 *
 * <p>只抽文本；内嵌图片由 {@code media/ImageExtractor} 单独处理（各自独立，不耦合）。</p>
 */
final class PptxBlockParser {

    private static final String CELL_SEPARATOR = " —— ";

    private PptxBlockParser() {
    }

    static List<ParsedBlock> parse(Path file) throws IOException {
        List<ParsedBlock> blocks = new ArrayList<>();
        try (XMLSlideShow slideShow = new XMLSlideShow(Files.newInputStream(file))) {
            int page = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                page++;
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTable table) {
                        tableBlocks(table, page, blocks);
                    } else if (shape instanceof XSLFTextShape textShape) {
                        textBlocks(textShape, page, blocks);
                    }
                }
            }
        }
        return blocks;
    }

    private static void textBlocks(XSLFTextShape shape, int page, List<ParsedBlock> blocks) {
        boolean title = isTitle(shape);
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            int indent = Math.max(0, paragraph.getIndentLevel());
            ParsedBlock.Kind kind = title ? ParsedBlock.Kind.HEADING
                    : (indent > 0 ? ParsedBlock.Kind.BULLET : ParsedBlock.Kind.PARAGRAPH);
            blocks.add(new ParsedBlock(kind, text.strip(), page, title ? 1 : indent + 1));
        }
    }

    private static void tableBlocks(XSLFTable table, int page, List<ParsedBlock> blocks) {
        for (XSLFTableRow row : table.getRows()) {
            StringBuilder line = new StringBuilder();
            for (XSLFTableCell cell : row.getCells()) {
                String text = cell.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (line.length() > 0) {
                    line.append(CELL_SEPARATOR);
                }
                line.append(text.strip().replace('\n', ' '));
            }
            if (line.length() > 0) {
                blocks.add(new ParsedBlock(ParsedBlock.Kind.TABLE_ROW, line.toString(), page, 0));
            }
        }
    }

    /** 标题占位符 → 当成章节标题（ChunkSplitter 会据此维护 sectionPath）。 */
    private static boolean isTitle(XSLFTextShape shape) {
        try {
            if (!shape.isPlaceholder()) {
                return false;
            }
            Placeholder placeholder = shape.getPlaceholder();
            return placeholder == Placeholder.TITLE || placeholder == Placeholder.CENTERED_TITLE;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
