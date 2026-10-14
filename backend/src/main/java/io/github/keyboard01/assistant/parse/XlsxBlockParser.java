package io.github.keyboard01.assistant.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * XLSX 解析：每个工作表产出一行"表名"标题，其余按行成块。
 *
 * <p>沿用 {@link DocxBlockParser} 的表格约定（单元格用 {@code " ｜ "} 连、表头拼在行首并以
 * {@code " —— "} 分隔），所以 {@link ChunkSplitter} 的表头复读机制不需要为 xlsx 写第二套逻辑。</p>
 *
 * <p><b>表名为什么单独出一个 HEADING</b>：一张表里的行单看往往是"HYZ-40 ｜ 25 MPa ｜ 40 L/min"，
 * 不说清是哪张表就只能靠猜。章节路径会随片段一起向量化（见 {@link ParsedChunk#embedText()}），
 * 表名进那里之后，"参数表怎么规定的"这类问题才可能召回得到。</p>
 *
 * <p><b>为什么 page=0</b>：电子表格没有页码语义，与 DOCX 同一条口径（上层把 0 转成 null）。
 * 硬编 sheet 序号会与"PDF 才有页码"的既有约定冲突（验收脚本 AC-P2-009 会因此失败），
 * 而 sheet 名已经由标题块承担了定位职责。</p>
 *
 * <p><b>取值用 {@link DataFormatter}</b>：拿"用户在该单元格里看到的文本"——日期、百分数、
 * 带千分位的数字在底层都是数字，直接读原始值会变成一串看不懂的浮点数。</p>
 *
 * <p>刻意不做的事：公式不求值（只取缓存值）、图片与图表不抽取、合并单元格不还原结构。
 * 这些都属于"看起来更聪明"，猜错就会把内容排错位。</p>
 */
final class XlsxBlockParser {

    private static final Logger log = LoggerFactory.getLogger(XlsxBlockParser.class);

    /** 表头拼在行首的长度上限，与 {@link DocxBlockParser} 保持一致。 */
    private static final int HEADER_PREFIX_LIMIT = 400;

    /** 单个单元格保留的字符数：长文本单元格（如备注）截断，避免一行把片段预算吃光。 */
    private static final int CELL_CHAR_LIMIT = 500;

    /** 护栏：单个工作表的行数、整份文件的总行数。触顶只截断并记日志，不让整份文档失败。 */
    private static final int MAX_ROWS_PER_SHEET = 5000;
    private static final int MAX_ROWS_TOTAL = 50000;

    private XlsxBlockParser() {
    }

    static List<ParsedBlock> parse(Path file) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            DataFormatter formatter = new DataFormatter();
            List<ParsedBlock> blocks = new ArrayList<>();
            int totalRows = 0;
            int order = 0;
            for (Sheet sheet : workbook) {
                if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                    continue;
                }
                List<List<String>> rows = rows(sheet, formatter);
                if (rows.isEmpty()) {
                    continue;
                }
                blocks.add(new ParsedBlock(ParsedBlock.Kind.HEADING, heading(++order, sheet.getSheetName()), 0, 1));
                int usable = Math.min(rows.size(), MAX_ROWS_PER_SHEET);
                if (rows.size() > usable) {
                    log.warn("[解析] 工作表「{}」共 {} 行，只取前 {} 行", sheet.getSheetName(), rows.size(), usable);
                }
                if (totalRows + usable > MAX_ROWS_TOTAL) {
                    usable = Math.max(0, MAX_ROWS_TOTAL - totalRows);
                    log.warn("[解析] 表格总行数触顶（{}），工作表中剩余的 {} 行未入库", MAX_ROWS_TOTAL,
                            sheet.getSheetName());
                }
                if (usable == 0) {
                    break;
                }
                blocks.addAll(rowBlocks(rows.subList(0, usable)));
                totalRows += usable;
            }
            return blocks;
        }
    }

    /**
     * 行块：首个非空行（多于一格且有后续行时）当表头，其余行把表头拼在行首。
     *
     * <p>首行只有一格时不当表头——那多半是"某某参数表"这类大标题，拿它给每一行做前缀是噪声，
     * 而且会把它自己弄丢。此时它按普通行原样发出（宁可让用户看到一行标题，也不要静默丢内容）。</p>
     */
    static List<ParsedBlock> rowBlocks(List<List<String>> rows) {
        List<ParsedBlock> blocks = new ArrayList<>();
        if (rows.isEmpty()) {
            return blocks;
        }
        List<String> first = rows.get(0);
        // DocxBlockParser 判表头用"多于一个单元格"；这里同样按"多于一个非空单元格"判
        boolean useHeader = rows.size() > 1 && first.size() > 1;
        // 不当表头时从第 0 行开始发（标题行原样保留），当表头时它只作为前缀出现、不单独成块
        int start = useHeader ? 1 : 0;
        String header = String.join(" ｜ ", first);
        for (int i = start; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            if (cells.isEmpty()) {
                continue;
            }
            String row = String.join(" ｜ ", cells);
            if (useHeader && header.length() + row.length() + 3 <= HEADER_PREFIX_LIMIT) {
                blocks.add(new ParsedBlock(ParsedBlock.Kind.TABLE_ROW, header + " —— " + row, 0, 0));
            } else {
                blocks.add(row(cells));
            }
        }
        return blocks;
    }

    private static ParsedBlock row(List<String> cells) {
        return new ParsedBlock(ParsedBlock.Kind.TABLE_ROW, String.join(" ｜ ", cells), 0, 0);
    }

    /**
     * 表名编号："2. 换油周期"。
     *
     * <p><b>为什么带编号。</b>{@link ChunkSplitter} 判标题层级时不看 {@code level} 字段，而是按文本自己推：
     * 有编号的按编号定级，没有的按"当前深度 + 1"。工作表之间是**平级**的，不编号就会一路嵌套，
     * 章节路径变成"液压站参数 &gt; 换油周期 &gt; 故障代码 &gt; 备注"——最后那张表的内容会挂着三张不相干的表名进向量。
     * 编号同时让重排出来的 PDF 有"第几张表"的顺序感。</p>
     *
     * <p>编号数只数**有内容的**表，所以中间的空表不会让编号出现断档。</p>
     */
    static String heading(int order, String sheetName) {
        String name = HeadingRules.clean(sheetName);
        return name.isEmpty() ? order + ". 工作表" : order + ". " + name;
    }

    /** 读出非空行；整行都是空白则丢弃（表格里成片的空行没有语义，还会稀释片段预算）。 */
    private static List<List<String>> rows(Sheet sheet, DataFormatter formatter) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < row.getLastCellNum(); i++) {
                // RETURN_BLANK_AS_NULL：公式无缓存值时不能当成空串，否则整行看起来都是空的
                var cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : HeadingRules.clean(formatter.formatCellValue(cell));
                if (value.length() > CELL_CHAR_LIMIT) {
                    value = value.substring(0, CELL_CHAR_LIMIT);
                }
                if (!value.isEmpty()) {
                    cells.add(value);
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }
}
