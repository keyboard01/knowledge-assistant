package io.github.keyboard01.assistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 切分粒度是检索质量的第一变量（见《需求说明与落地清单》§11.1 的实测：同一句"一线城市指北京、上海、
 * 广州、深圳。"埋在整页 802 字里余弦只有 0.21，独立成块是 0.75）。
 *
 * <p>这几条把切分规则钉死：token 预算（min/target/max）、长段落按 target 打包、同章节路径同类型的相邻
 * 短块合并、overlap、表格拆行重复表头、标题下无正文则标题自成一块。粒度一旦回退，后面怎么调阈值都是
 * 换一种错法。</p>
 */
class ChunkSplitterTest {

    private static ParsedChunk paragraph(String text, int page) {
        return ChunkSplitter.split(List.of(new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, text, page, 0))).get(0);
    }

    @Test
    @DisplayName("同一章节路径、同类型、同页的相邻短段落会合并（对标 movo 的 merge）")
    void mergesAdjacentShortParagraphsInSameSection() {
        List<ParsedBlock> blocks = List.of(
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "注 2：一线城市指北京、上海、广州、深圳。", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "住宿标准见下表。", 1, 0));

        List<ParsedChunk> chunks = ChunkSplitter.split(blocks);

        assertEquals(1, chunks.size(), "两段都短且同路径同页，应合并成一块");
        assertEquals("注 2：一线城市指北京、上海、广州、深圳。\n\n住宿标准见下表。", chunks.get(0).content());
        assertEquals(1, chunks.get(0).pageFrom());
        assertEquals(1, chunks.get(0).pageTo());
    }

    @Test
    @DisplayName("不同章节路径的相邻短段不合并——路径不同就是不同语义域")
    void doesNotMergeAcrossSections() {
        List<ParsedBlock> blocks = List.of(
                new ParsedBlock(ParsedBlock.Kind.HEADING, "1 总则", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "甲。", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.HEADING, "2 附则", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "乙。", 1, 0));

        List<ParsedChunk> chunks = ChunkSplitter.split(blocks);

        assertEquals(2, chunks.size());
        assertEquals("甲。", chunks.get(0).content());
        assertEquals("乙。", chunks.get(1).content());
        assertNotEquals(chunks.get(0).sectionPath(), chunks.get(1).sectionPath());
    }

    @Test
    @DisplayName("跨页的相邻短段不合并——别把两页的话粘一起")
    void doesNotMergeAcrossPages() {
        List<ParsedBlock> blocks = List.of(
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "甲。", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "乙。", 2, 0));

        assertEquals(2, ChunkSplitter.split(blocks).size());
    }

    @Test
    @DisplayName("不同语义单元不合并：正文与表格行各成一块，哪怕都很短")
    void differentKindsAreNotMerged() {
        List<ParsedBlock> blocks = List.of(
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "正文一。", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.TABLE_ROW, "周期 —— 维护项目", 1, 0));

        List<ParsedChunk> chunks = ChunkSplitter.split(blocks);

        assertEquals(2, chunks.size());
        assertEquals("PARAGRAPH", chunks.get(0).kind());
        assertEquals("TABLE_ROW", chunks.get(1).kind());
    }

    @Test
    @DisplayName("超预算的长段落按 target 打包，碎尾并回上一片")
    void packsLongParagraphTowardTarget() {
        String a = "甲".repeat(58) + "。";
        String b = "乙".repeat(58) + "。";
        String c = "丙".repeat(58) + "。";
        String d = "丁".repeat(58) + "。";
        String tiny = "不适用本办法。";
        String text = a + b + c + d + tiny;

        List<ParsedChunk> chunks = ChunkSplitter.split(List.of(new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, text, 2, 0)));

        assertEquals(2, chunks.size(), "四句 + 碎尾应打包成两片，而不是逐句成块");
        assertEquals(a + b, chunks.get(0).content(), "第一片不带 overlap");
        assertTrue(chunks.get(1).content().contains(c), "第二片要含第三句");
        assertTrue(chunks.get(1).content().endsWith(d + tiny), "不足 MIN_STANDALONE 的碎尾并回上一片");
    }

    @Test
    @DisplayName("相邻片段带 overlap：后一片以片尾的重复文本开头")
    void piecesOverlapAtBoundary() {
        String s1 = "甲".repeat(120) + "。";
        String s2 = "乙".repeat(120) + "。";

        List<ParsedChunk> chunks = ChunkSplitter.split(
                List.of(new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, s1 + s2, 1, 0)));

        assertEquals(2, chunks.size());
        assertEquals(s1, chunks.get(0).content());
        assertTrue(chunks.get(1).content().startsWith("甲".repeat(29) + "。"),
                "第二片应以前一片尾部的重叠文本开头");
    }

    @Test
    @DisplayName("单句就超长时按字符硬切，不丢内容、也不加 overlap")
    void hardCutsAnOverlongSentence() {
        String monster = "甲".repeat(450);

        List<ParsedChunk> chunks = ChunkSplitter.split(List.of(new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, monster, 1, 0)));

        assertEquals(3, chunks.size());
        assertEquals(200, chunks.get(0).content().length());
        assertEquals(200, chunks.get(1).content().length());
        assertEquals(50, chunks.get(2).content().length());
    }

    @Test
    @DisplayName("表格行被拆分时，表头重复加到后续每一片前")
    void repeatsTableHeaderWhenSplittingRow() {
        String header = "表头";
        String row = "甲".repeat(230) + "。";
        String text = header + " —— " + row;

        List<ParsedChunk> chunks = ChunkSplitter.split(
                List.of(new ParsedBlock(ParsedBlock.Kind.TABLE_ROW, text, 1, 0)));

        assertTrue(chunks.size() >= 2, "该行超预算，应被拆成多片");
        assertTrue(chunks.get(0).content().startsWith(header + " —— "));
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).content().startsWith(header + " —— "),
                    "第 " + (i + 1) + " 片应重复表头");
        }
    }

    @Test
    @DisplayName("章节路径按编号层级累积：叶子片段带上完整路径 1 总则 > 1.1 适用范围")
    void accumulatesSectionPathByNumbering() {
        List<ParsedBlock> blocks = List.of(
                new ParsedBlock(ParsedBlock.Kind.HEADING, "1 总则", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.HEADING, "1.1 适用范围", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "本制度适用于全体员工。", 2, 0));

        List<ParsedChunk> chunks = ChunkSplitter.split(blocks);

        // "1 总则"下面还没等到正文就又来了个子标题，所以它先以"光杆标题"成块——这正是要保留的行为
        assertEquals(2, chunks.size());
        assertEquals("HEADING", chunks.get(0).kind());
        assertEquals("1 总则", chunks.get(0).content());

        ParsedChunk leaf = chunks.get(1);
        assertEquals("1 总则 > 1.1 适用范围", leaf.sectionPath());
        assertEquals(2, leaf.level());
        assertEquals("PARAGRAPH", leaf.kind());
        assertEquals("本制度适用于全体员工。", leaf.content());
        assertEquals("1 总则 > 1.1 适用范围\n本制度适用于全体员工。", leaf.embedText(),
                "向量化输入要带章节路径前缀，展示只留正文");
    }

    @Test
    @DisplayName("标题下没有正文时，标题自己成块——否则\"某章讲了什么\"连候选都没有")
    void headingWithoutBodyBecomesItsOwnChunk() {
        List<ParsedChunk> chunks = ChunkSplitter.split(
                List.of(new ParsedBlock(ParsedBlock.Kind.HEADING, "4 费用标准", 3, 0)));

        assertEquals(1, chunks.size());
        assertEquals("HEADING", chunks.get(0).kind());
        assertEquals("4 费用标准", chunks.get(0).content());
        assertEquals("4 费用标准", chunks.get(0).sectionPath());
        assertEquals(1, chunks.get(0).level());
    }

    @Test
    @DisplayName("页码为 0（DOCX 无分页语义）时留空，绝不瞎填")
    void zeroPageBecomesNull() {
        ParsedChunk chunk = paragraph("无页码语义的正文。", 0);

        assertNull(chunk.pageFrom());
        assertNull(chunk.pageTo());
    }

    @Test
    @DisplayName("levelOf：数字编号按点数定级，\"第X章/一、\"算一级，无编号按当前深度 +1 且封顶 3")
    void levelOfRules() {
        assertEquals(1, ChunkSplitter.levelOf("第一章 总则", 0));
        assertEquals(1, ChunkSplitter.levelOf("一、总则", 0));
        assertEquals(3, ChunkSplitter.levelOf("3.4.1 报销流程", 0));
        assertEquals(2, ChunkSplitter.levelOf("审批权限", 1));
        assertEquals(3, ChunkSplitter.levelOf("审批权限", 5));
    }

    @Test
    @DisplayName("空块直接跳过，不产生空片段")
    void blankBlocksAreSkipped() {
        List<ParsedChunk> chunks = ChunkSplitter.split(List.of(
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "   ", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "有内容。", 1, 0)));

        assertEquals(1, chunks.size());
        assertEquals("有内容。", chunks.get(0).content());
    }

    @Test
    @DisplayName("estimateTokens：空串为 0，中文按字、英文按词，混排相加")
    void estimatesTokens() {
        assertEquals(0, ChunkSplitter.estimateTokens(""));
        assertEquals(0, ChunkSplitter.estimateTokens(null));
        assertEquals(3, ChunkSplitter.estimateTokens("北京"));
        assertEquals(3, ChunkSplitter.estimateTokens("hello world"));
        assertTrue(ChunkSplitter.estimateTokens("甲".repeat(100)) > ChunkSplitter.estimateTokens("甲".repeat(10)));
    }

    @Test
    @DisplayName("ChunkOptions.of 归一化：保证 1<=min<=target<=max，overlap<target")
    void chunkOptionsNormalizes() {
        assertEquals(new ChunkOptions(60, 150, 200, 30), ChunkOptions.of(60, 150, 200, 30));
        assertEquals(new ChunkOptions(50, 50, 50, 49), ChunkOptions.of(500, 100, 50, 999));
        assertEquals(new ChunkOptions(1, 1, 1, 0), ChunkOptions.of(0, 0, 0, -5));
    }
}
