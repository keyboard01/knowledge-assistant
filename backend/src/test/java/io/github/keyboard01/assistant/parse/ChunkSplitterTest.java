package io.github.keyboard01.assistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 切分粒度是检索质量的第一变量（见《需求说明与落地清单》§11.1 的实测：同一句"一线城市指北京、上海、
 * 广州、深圳。"埋在整页 802 字里余弦只有 0.21，独立成块是 0.75）。
 *
 * <p>这几条把四条规则钉死：不跨语义单元合并、长段按句拆、碎句并入上一块、标题下无正文则标题自成一块。
 * 粒度一旦回退，后面怎么调阈值都是换一种错法。</p>
 */
class ChunkSplitterTest {

    @Test
    @DisplayName("连续短段各自成块，绝不打包——打包正是把定义句重新埋回长块的元凶")
    void shortParagraphsAreNotPackedTogether() {
        List<ParsedBlock> blocks = List.of(
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "注 2：一线城市指北京、上海、广州、深圳。", 1, 0),
                new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "住宿标准见下表。", 1, 0));

        List<ParsedChunk> chunks = ChunkSplitter.split(blocks);

        assertEquals(2, chunks.size());
        assertEquals("注 2：一线城市指北京、上海、广州、深圳。", chunks.get(0).content());
        assertEquals("住宿标准见下表。", chunks.get(1).content());
        assertEquals(1, chunks.get(0).pageFrom());
        assertEquals(1, chunks.get(0).pageTo());
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
    @DisplayName("超过 maxChars 的段落按句拆：长句各自成块，碎句并回上一块")
    void splitsLongParagraphBySentence() {
        String a = "甲".repeat(58) + "。";
        String b = "乙".repeat(58) + "。";
        String c = "丙".repeat(58) + "。";
        String d = "丁".repeat(58) + "。";
        String tiny = "不适用本办法。";
        String text = a + b + c + d + tiny;
        assertEquals(243, text.length());

        List<ParsedChunk> chunks = ChunkSplitter.split(List.of(new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, text, 2, 0)));

        assertEquals(4, chunks.size());
        assertEquals(a, chunks.get(0).content());
        assertEquals(b, chunks.get(1).content());
        assertEquals(c, chunks.get(2).content());
        assertEquals(d + tiny, chunks.get(3).content(), "不足 15 字的碎句必须并回上一块");
    }

    @Test
    @DisplayName("单句就超长时按字符硬切，不丢内容")
    void hardCutsAnOverlongSentence() {
        String monster = "甲".repeat(450);

        List<ParsedChunk> chunks = ChunkSplitter.split(List.of(new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, monster, 1, 0)));

        assertEquals(3, chunks.size());
        assertEquals(200, chunks.get(0).content().length());
        assertEquals(200, chunks.get(1).content().length());
        assertEquals(50, chunks.get(2).content().length());
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
        List<ParsedChunk> chunks = ChunkSplitter.split(
                List.of(new ParsedBlock(ParsedBlock.Kind.PARAGRAPH, "无页码语义的正文。", 0, 0)));

        assertNull(chunks.get(0).pageFrom());
        assertNull(chunks.get(0).pageTo());
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
}