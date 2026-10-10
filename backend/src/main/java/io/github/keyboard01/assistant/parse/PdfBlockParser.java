package io.github.keyboard01.assistant.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDF 解析：逐页取行（带字号、字体与横向位置），判标题、并段落、丢页眉页脚。
 *
 * <p>PDF 里没有"段落"也没有"表格"，只有一堆带坐标的字形。这里的做法是先按 y 坐标归行，
 * 再用三个信号判断版式意图：</p>
 * <ul>
 *   <li><b>整行（顶到右边距）且上一行没有句末标点</b> → 是折行，续到同一段；</li>
 *   <li><b>列位置一致（x0 相同）</b> → 同一列；表格行的 x0 与正文不同，靠它区分"表格的一行"和"正文的折行"；</li>
 *   <li><b>跨页重复的行 + 含"第 N 页"的行</b> → 页眉页脚，丢掉。</li>
 * </ul>
 *
 * <p>这套规则只依赖版式，不依赖某一份语料。代价是 PDF 里没有真正的表格结构——
 * 我们只把表格的每一行拆成独立的块，恢复不出"表头—单元格"关系，这一点在文档里如实登记。</p>
 */
public final class PdfBlockParser {

    /** 一行的几何与样式信息。 */
    record Line(String text, double size, boolean bold, double left, double right) {
        boolean reachesRightEdge(double pageRight) {
            return pageRight > 0 && text.length() >= 25 && right >= pageRight * 0.92;
        }

        static boolean endsSentence(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            char last = text.charAt(text.length() - 1);
            return last == '。' || last == '；' || last == '！' || last == '？' || last == '.' || last == ';';
        }
    }

    /** 页脚里的页码标记（"第 1 页"），跨页不重复也能认出来。 */
    private static final Pattern PAGE_MARK = Pattern.compile("第\\s*\\d+\\s*页");

    private PdfBlockParser() {
    }

    public static List<ParsedBlock> parse(Path file) throws IOException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            List<List<Line>> pages = new ArrayList<>();
            for (int pageNo = 1; pageNo <= document.getNumberOfPages(); pageNo++) {
                pages.add(linesOfPage(document, pageNo));
            }
            return blocksOf(pages);
        }
    }

    private static List<Line> linesOfPage(PDDocument document, int pageNo) throws IOException {
        LineCollector stripper = new LineCollector();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        stripper.getText(document); // 触发提取；字形收集在 getCharactersByArticle 里

        Map<Integer, List<TextPosition>> byLine = new TreeMap<>();
        for (List<TextPosition> article : stripper.articles()) {
            for (TextPosition glyph : article) {
                byLine.computeIfAbsent(Math.round(glyph.getYDirAdj()), key -> new ArrayList<>()).add(glyph);
            }
        }
        List<Line> lines = new ArrayList<>();
        for (List<TextPosition> glyphs : byLine.values()) {
            glyphs.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
            StringBuilder text = new StringBuilder();
            for (TextPosition glyph : glyphs) {
                if (glyph.getUnicode() != null) {
                    text.append(glyph.getUnicode());
                }
            }
            String value = HeadingRules.clean(text.toString());
            if (value.isEmpty()) {
                continue;
            }
            TextPosition first = glyphs.get(0);
            TextPosition last = glyphs.get(glyphs.size() - 1);
            String font = first.getFont() == null ? "" : first.getFont().getName();
            lines.add(new Line(value, first.getFontSizeInPt(), isBoldFont(font),
                    first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj()));
        }
        return lines;
    }

    /** 加粗只能靠字体名判：无样式的 PDF 里字体名是唯一线索（本语料是 MicrosoftYaHei-Bold / SimHei）。 */
    static boolean isBoldFont(String fontName) {
        String name = fontName == null ? "" : fontName.toLowerCase(Locale.ROOT);
        return name.contains("bold") || name.contains("heavy") || name.contains("black")
                || name.contains("simhei") || name.contains("heiti");
    }

    /**
     * 行 → 块。跨页重复的行按"重复出现"判定页眉（换一份文档也不会失效），
     * 页脚则靠页码标记——页脚每页的页码不同，重复判定抓不住它。
     */
    static List<ParsedBlock> blocksOf(List<List<Line>> pages) {
        double bodySize = bodySize(pages);
        Map<String, Integer> appearances = new HashMap<>();
        for (List<Line> page : pages) {
            page.stream().map(Line::text).distinct()
                    .forEach(text -> appearances.merge(text, 1, Integer::sum));
        }

        List<ParsedBlock> blocks = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            List<Line> page = pages.get(index);
            int pageNo = index + 1;
            double pageRight = page.stream().mapToDouble(Line::right).max().orElse(0);
            List<Line> content = new ArrayList<>();
            for (Line line : page) {
                boolean boilerplate = appearances.getOrDefault(line.text(), 0) > 1
                        || PAGE_MARK.matcher(line.text()).find();
                if (!boilerplate) {
                    content.add(line);
                }
            }

            StringBuilder paragraph = new StringBuilder();
            Line previous = null;
            Line blockFirst = null;
            int blockLines = 0;
            for (Line line : content) {
                boolean heading = HeadingRules.isHeading(line.text(), line.size(), bodySize, line.bold());
                boolean bullet = HeadingRules.isBullet(line.text());
                boolean continues = previous != null
                        && previous.reachesRightEdge(pageRight)
                        && !Line.endsSentence(previous.text())
                        && Math.abs(line.left() - previous.left()) <= 3;
                if (!heading && !bullet && continues) {
                    paragraph.append(line.text());
                    blockLines++;
                } else {
                    // 块类型在收块时判：只有"独占一行的短行"才像表格行，
                    // 折行拼起来的段落不能因为其中含数字就被标成表格行（那会把同一段话切两半）
                    flush(blocks, paragraph, pageNo, kindOf(blockFirst, blockLines));
                    blockFirst = null;
                    blockLines = 0;
                    if (heading) {
                        blocks.add(new ParsedBlock(ParsedBlock.Kind.HEADING, line.text(), pageNo, 0));
                    } else if (bullet) {
                        blocks.add(new ParsedBlock(ParsedBlock.Kind.BULLET, line.text(), pageNo, 0));
                    } else {
                        paragraph.append(line.text());
                        blockFirst = line;
                        blockLines = 1;
                    }
                }
                previous = line;
            }
            flush(blocks, paragraph, pageNo, kindOf(blockFirst, blockLines));
        }
        return blocks;
    }

    /**
     * 疑似表格行：单独一行、不到 60 字、带数字、不是完整句子。
     * 这只是给块打标签用的启发式（PDF 里没有表格结构可依），标错了不影响切分粒度。
     */
    private static boolean looksLikeTableRow(Line line) {
        String text = line.text();
        return text.length() <= 60
                && !Line.endsSentence(text)
                && text.matches(".*\\d.*")
                && !HeadingRules.isBullet(text);
    }

    private static ParsedBlock.Kind kindOf(Line first, int lineCount) {
        return lineCount == 1 && first != null && looksLikeTableRow(first)
                ? ParsedBlock.Kind.TABLE_ROW
                : ParsedBlock.Kind.PARAGRAPH;
    }

    private static void flush(List<ParsedBlock> blocks, StringBuilder paragraph, int pageNo,
                              ParsedBlock.Kind kind) {
        String text = HeadingRules.clean(paragraph.toString());
        paragraph.setLength(0);
        if (text.length() >= 2) {
            blocks.add(new ParsedBlock(kind, text, pageNo, 0));
        }
    }

    /** 正文字号 = 按字符数加权的中位字号。 */
    static double bodySize(List<List<Line>> pages) {
        List<Double> sizes = new ArrayList<>();
        for (List<Line> page : pages) {
            for (Line line : page) {
                if (line.text().length() >= 8) {
                    for (int i = 0; i < line.text().length(); i++) {
                        sizes.add(line.size());
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

    /** 只为拿到 {@code getCharactersByArticle()}（protected）而继承。 */
    private static final class LineCollector extends PDFTextStripper {
        LineCollector() throws IOException {
            super();
        }

        List<List<TextPosition>> articles() {
            return getCharactersByArticle();
        }
    }
}