package io.github.keyboard01.assistant.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用解析是"编造来源"的唯一拦截点：这里放过一个越界编号，前端就会出现指向不存在文档的引用卡片。
 */
class CitationCheckTest {

    @Test
    @DisplayName("半角与全角括号都认，按首次出现顺序去重")
    void parsesBothBracketStylesAndDeduplicates() {
        CitationCheck.Result result = CitationCheck.parse("住宿标准是 500 元 [2]，另有补充 【1】，再次提到 [2]。", 3);

        assertEquals(List.of(2, 1), result.cited());
        assertEquals(List.of(), result.outOfRange());
        assertTrue(result.hasCitation());
    }

    @Test
    @DisplayName("0 与超出证据数量的编号一律算越界")
    void reportsOutOfRangeRanks() {
        CitationCheck.Result result = CitationCheck.parse("按 [0] 与 [7] 的说法", 3);

        assertFalse(result.hasCitation());
        assertEquals(List.of(0, 7), result.outOfRange());
        assertTrue(result.hasOutOfRange());
    }

    @Test
    @DisplayName("非纯数字标记不算引用：宁可判成没有引用，也不猜它想引用哪几条")
    void ignoresNonNumericMarkers() {
        CitationCheck.Result result = CitationCheck.parse("见 [a]、[1,2]、[1-3] 与 [备注]", 3);

        assertFalse(result.hasCitation());
        assertFalse(result.hasOutOfRange());
    }

    @Test
    @DisplayName("空回答不炸，且不算有引用")
    void toleratesNullOrBlank() {
        assertFalse(CitationCheck.parse(null, 3).hasCitation());
        assertFalse(CitationCheck.parse("   ", 3).hasCitation());
    }
}
