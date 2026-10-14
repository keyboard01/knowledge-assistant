package io.github.keyboard01.assistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * XLSX 解析：工作表名成标题、行成块且带表头前缀、页码一律为空。
 *
 * <p>这里盯的三件事坏起来都不报错：表头没拼上去，检索时"25 MPa"这种行谁也说不清是什么；
 * sheet 名丢了，跨表的行混在一起；页码编成 sheet 序号，会与"PDF 才有页码"的约定冲突。</p>
 */
class XlsxBlockParserTest {

    private static void writeSheet(XSSFWorkbook workbook, String name, String[][] rows) {
        Sheet sheet = workbook.createSheet(name);
        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                row.createCell(c).setCellValue(rows[r][c]);
            }
        }
    }

    @Test
    @DisplayName("表名成标题、首行当表头拼在每行行首、页码为空")
    void parsesSheetWithHeader(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("params.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeSheet(workbook, "主要参数", new String[][] {
                    {"型号", "额定压力", "流量"},
                    {"HYZ-40", "25 MPa", "40 L/min"},
                    {"HYZ-63", "31.5 MPa", "63 L/min"}});
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<ParsedBlock> blocks = XlsxBlockParser.parse(file);

        assertTrue(blocks.stream().anyMatch(block -> block.kind() == ParsedBlock.Kind.HEADING
                && block.text().equals("1. 主要参数")), "工作表名要成标题块且带编号：" + blocks);
        assertTrue(blocks.stream().anyMatch(block -> block.kind() == ParsedBlock.Kind.TABLE_ROW
                && block.text().equals("型号 ｜ 额定压力 ｜ 流量 —— HYZ-40 ｜ 25 MPa ｜ 40 L/min")),
                "数据行要带表头前缀与单元格分隔符：" + blocks);
        // 表头自己不再单独成块（它已经作为前缀出现，再来一条就是重复内容）
        assertFalse(blocks.stream().anyMatch(block -> block.text().equals("型号 ｜ 额定压力 ｜ 流量")),
                "表头不应单独成块：" + blocks);
        assertTrue(blocks.stream().allMatch(block -> block.page() == 0),
                "xlsx 没有页码语义，一律给 0（上层转 null）：" + blocks);
    }

    @Test
    @DisplayName("多工作表：每张表都有自己的标题，行不会串在一起")
    void keepsSheetsSeparate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("multi.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeSheet(workbook, "保养周期", new String[][] {{"项目", "周期"}, {"换油", "2000h"}});
            writeSheet(workbook, "故障代码", new String[][] {{"代码", "含义"}, {"E01", "油温过高"}});
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<ParsedBlock> blocks = XlsxBlockParser.parse(file);
        List<String> headings = blocks.stream()
                .filter(block -> block.kind() == ParsedBlock.Kind.HEADING)
                .map(ParsedBlock::text)
                .toList();
        assertEquals(List.of("1. 保养周期", "2. 故障代码"), headings);
        assertTrue(blocks.stream().anyMatch(block -> block.text().contains("E01") && block.text().contains("油温过高")));
    }

    @Test
    @DisplayName("工作表之间是平级：章节路径不能一路嵌套下去")
    void sheetSectionsAreSiblings(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("siblings.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeSheet(workbook, "参数", new String[][] {{"项", "值"}, {"压力", "25 MPa"}});
            writeSheet(workbook, "故障", new String[][] {{"代码", "含义"}, {"E01", "油温过高"}});
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        // 走真实的切分：标题层级是 ChunkSplitter 按文本推的，不编号就会嵌套（这条就是为此加的）
        List<ParsedChunk> chunks = ChunkSplitter.split(XlsxBlockParser.parse(file));
        ParsedChunk second = chunks.stream()
                .filter(chunk -> chunk.content().contains("E01"))
                .findFirst()
                .orElseThrow();
        assertEquals("2. 故障", second.sectionPath(), "第二张表的章节路径不该带上第一张表：" + chunks);
    }

    @Test
    @DisplayName("单行工作表不当表头：内容不能因此丢掉")
    void singleRowIsKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("single.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeSheet(workbook, "备注", new String[][] {{"仅一行说明"}});
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<ParsedBlock> blocks = XlsxBlockParser.parse(file);
        assertTrue(blocks.stream().anyMatch(block -> block.kind() == ParsedBlock.Kind.TABLE_ROW
                && block.text().equals("仅一行说明")), "单行不能被当成表头吃掉：" + blocks);
    }

    @Test
    @DisplayName("空行与空工作表不产生块")
    void skipsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("空表");
            writeSheet(workbook, "有内容", new String[][] {{"项", "值"}, {"", ""}, {"油位", "正常"}});
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<ParsedBlock> blocks = XlsxBlockParser.parse(file);
        assertFalse(blocks.stream().anyMatch(block -> block.text().equals("空表")), "空工作表不出标题：" + blocks);
        assertFalse(blocks.stream().anyMatch(block -> block.text().endsWith("—— ")), "空行不出块：" + blocks);
        assertTrue(blocks.stream().anyMatch(block -> block.text().contains("油位")));
    }

    @Test
    @DisplayName("数字按显示值取，不是底层浮点数")
    void numbersUseDisplayValue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("numbers.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("金额");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("项目");
            header.createCell(1).setCellValue("金额");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("差旅");
            row.createCell(1).setCellValue(500);
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<ParsedBlock> blocks = XlsxBlockParser.parse(file);
        assertTrue(blocks.stream().anyMatch(block -> block.text().contains("500")), blocks.toString());
        assertFalse(blocks.stream().anyMatch(block -> block.text().contains("500.0")),
                "整数不该变成 500.0 这种底层浮点写法：" + blocks);
    }
}
