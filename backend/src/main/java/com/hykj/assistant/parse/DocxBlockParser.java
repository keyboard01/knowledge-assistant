package com.hykj.assistant.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * DOCX 解析：按正文顺序遍历段落与表格，产出语义块。
 *
 * <p>DOCX 没有页码语义——Word 的分页是渲染结果，同一份文件在不同打印机/字体下页数都能变。
 * 所以这里一律给 page=0（上层转成 null）。要给 DOCX 编页码，只能自己再渲染一遍，
 * 那是"造一个看起来精确的假数"，不做。</p>
 *
 * <p>表格按行成块，并把表头拼在行首（"周期 ｜ 维护项目 ｜ 判定标准 —— 每 2000 h ｜ 更换回油滤芯 ｜ …"）：
 * 单看"每 2000 h"这一行，人和模型都不知道这一列是什么。</p>
 */
public final class DocxBlockParser {

    private static final int HEADER_PREFIX_LIMIT = 400;

    private DocxBlockParser() {
    }

    public static List<ParsedBlock> parse(Path file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            double bodySize = bodySize(document);
            List<ParsedBlock> blocks = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    ParsedBlock block = paragraphBlock(paragraph, bodySize);
                    if (block != null) {
                        blocks.add(block);
                    }
                } else if (element instanceof XWPFTable table) {
                    blocks.addAll(tableBlocks(table));
                }
            }
            return blocks;
        }
    }

    private static ParsedBlock paragraphBlock(XWPFParagraph paragraph, double bodySize) {
        String text = HeadingRules.clean(paragraph.getText());
        if (text.length() < 2) {
            return null;
        }
        double size = 0;
        boolean bold = false;
        for (XWPFRun run : paragraph.getRuns()) {
            double runSize = run.getFontSize();
            if (runSize > 0 && size == 0) {
                size = runSize;
            }
            bold = bold || run.isBold();
        }
        boolean styled = paragraph.getStyle() != null
                && paragraph.getStyle().toLowerCase(java.util.Locale.ROOT).contains("heading");
        if (styled || HeadingRules.isHeading(text, size, bodySize, bold)) {
            return new ParsedBlock(ParsedBlock.Kind.HEADING, text, 0, 0);
        }
        if (HeadingRules.isBullet(text)) {
            return new ParsedBlock(ParsedBlock.Kind.BULLET, text, 0, 0);
        }
        return new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, text, 0, 0);
    }

    private static List<ParsedBlock> tableBlocks(XWPFTable table) {
        List<ParsedBlock> blocks = new ArrayList<>();
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return blocks;
        }
        String header = cells(rows.get(0));
        boolean useHeader = rows.size() > 1 && rows.get(0).getTableCells().size() > 1;
        for (int i = 1; i < rows.size(); i++) {
            String row = cells(rows.get(i));
            if (row.isEmpty()) {
                continue;
            }
            String text = row;
            if (useHeader && !header.isEmpty() && header.length() + row.length() + 3 <= HEADER_PREFIX_LIMIT) {
                text = header + " —— " + row;
            }
            blocks.add(new ParsedBlock(ParsedBlock.Kind.TABLE_ROW, text, 0, 0));
        }
        return blocks;
    }

    private static String cells(XWPFTableRow row) {
        List<String> values = new ArrayList<>();
        for (XWPFTableCell cell : row.getTableCells()) {
            String value = HeadingRules.clean(cell.getText());
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return String.join(" ｜ ", values);
    }

    /** 正文字号 = 段落里出现次数最多的字号；标题只占少数，拉不偏它。 */
    static double bodySize(XWPFDocument document) {
        List<Double> sizes = new ArrayList<>();
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            if (HeadingRules.clean(paragraph.getText()).length() < 8) {
                continue;
            }
            for (XWPFRun run : paragraph.getRuns()) {
                double size = run.getFontSize();
                if (size > 0) {
                    for (int i = 0; i < Math.max(1, HeadingRules.clean(run.text()).length()); i++) {
                        sizes.add(size);
                    }
                }
            }
        }
        if (sizes.isEmpty()) {
            return 0;
        }
        sizes.sort(Double::compare);
        return sizes.get(sizes.size() / 2);
    }
}