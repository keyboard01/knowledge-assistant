package com.hykj.assistant.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hykj.assistant.parse.ParsedChunk;
import com.hykj.assistant.security.AccessFilter;
import com.hykj.assistant.security.AccessSubject;

/**
 * 检索 SQL 的占位符必须与绑定值一一对应。P2 实测踩过一次：用 {@code List.of(数组)} 想摊开数组，
 * 实际得到的是"一个装着数组的列表"，于是少绑一个值，运行时才报"未设定参数值 N 的内容"——
 * 只有连上库、走到那条分支才会炸，单测是唯一能提前拦住它的地方。
 */
class DocumentChunkRepositoryTest {

    /** 主体三项齐全（角色 + 用户名 + 部门），让 ACL 下推的占位符取到最多的那种情况。 */
    private static final AccessFilter FILTER =
            AccessFilter.of(AccessSubject.of("zhang.san", "viewer", "D01"), "d");

    @Test
    @DisplayName("向量通道：占位符数量等于绑定值数量（含 ACL 下推的参数）")
    void vectorQueryBindsEveryPlaceholder() {
        assertBalanced(DocumentChunkRepository.vectorQuery(FILTER, "policy", "[1.0,2.0]", 10, 0.25));
    }

    @Test
    @DisplayName("关键词通道：占位符数量等于绑定值数量")
    void keywordQueryBindsEveryPlaceholder() {
        assertBalanced(DocumentChunkRepository.keywordQuery(FILTER, "policy", "住宿费标准", 10, 0.12));
    }

    @Test
    @DisplayName("被挡下的命中计数：占位符与绑定值同样要一一对应")
    void blockedQueryBindsEveryPlaceholder() {
        assertBalanced(DocumentChunkRepository.blockedQuery(FILTER, "policy", "[1.0]", 0.51));
    }

    @Test
    @DisplayName("ACL 下推的参数确实进了绑定值（角色 / 用户名 / 部门）")
    void aclParamsAreCarriedOver() {
        DocumentChunkRepository.Query query =
                DocumentChunkRepository.vectorQuery(FILTER, "policy", "[1.0]", 5, 0.0);

        assertEquals(3, FILTER.paramsArray().length);
        assertTrue(Arrays.asList(query.params()).contains("viewer"));
        assertTrue(Arrays.asList(query.params()).contains("zhang.san"));
        assertTrue(Arrays.asList(query.params()).contains("D01"));
    }

    @Test
    @DisplayName("向量字面量拒绝 NaN/Infinity：库里存进这种值，之后每次检索都会被一条脏数据拖垮")
    void literalRejectsNonFinite() {
        assertThrows(IllegalStateException.class,
                () -> DocumentChunkRepository.literal(new float[]{1f, Float.NaN}));
        assertThrows(IllegalStateException.class,
                () -> DocumentChunkRepository.literal(new float[]{Float.POSITIVE_INFINITY}));
        assertEquals("[1.0,2.5]", DocumentChunkRepository.literal(new float[]{1f, 2.5f}));
    }

    @Test
    @DisplayName("片段写库是 upsert：靠 (document_id, chunk_index) 覆盖，不再删了重灌——"
            + "整份重建会换掉所有 id，历史账本的 chunk 引用当场悬空（实测 2554/2987）")
    void upsertKeepsChunkIdsStable() {
        String sql = DocumentChunkRepository.UPSERT_SQL;

        assertTrue(sql.contains("on conflict (document_id, chunk_index) do update"),
                "写路径必须落在唯一键上覆盖；退回 delete + insert 就等于每次重解析换一批 id：\n" + sql);
        assertFalse(sql.contains("delete"),
                "写路径里不该出现 delete：删整份会让账本里指向旧 id 的引用全部悬空：\n" + sql);
    }

    @Test
    @DisplayName("upsert 的占位符与批次列数一致：15 个值（embedded_at 走 now()，不占位）")
    void upsertBindsEveryPlaceholder() {
        long marks = DocumentChunkRepository.UPSERT_SQL.chars().filter(ch -> ch == '?').count();

        assertEquals(15, marks, "批次每行绑 15 个值（P1 上下文增强加了 context_prefix/context_hash），"
                + "占位符少一个就会在批处理时报\"未设定参数值\"：\n" + DocumentChunkRepository.UPSERT_SQL);
    }

    @Test
    @DisplayName("片段指纹 = 正文哈希:上下文哈希；无上下文时后半段为空，与引入上下文增强前的指纹等价")
    void fingerprintCombinesContentAndContext() {
        ParsedChunk chunk = new ParsedChunk(0, "PARAGRAPH", 1, null, null, null, "正文");

        assertEquals("abc:", DocumentChunkRepository.fingerprint(
                new DocumentChunkRepository.IndexedChunk(chunk, "abc", "", null)), "无上下文时后半段为空");
        assertEquals("abc:def", DocumentChunkRepository.fingerprint(
                new DocumentChunkRepository.IndexedChunk(chunk, "abc", "上下文", "def")));
    }

    private static void assertBalanced(DocumentChunkRepository.Query query) {
        long marks = query.sql().chars().filter(ch -> ch == '?').count();
        assertEquals(marks, query.params().length,
                "占位符与绑定值数量不一致（少绑一个就会在运行时报\"未设定参数值\"）：\n" + query.sql());
    }
}
