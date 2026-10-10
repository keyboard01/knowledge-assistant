package com.hykj.assistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型输出不可信，解析必须容错：包在 ```json 里、前后带解释、重复项、把原问题也塞回来，
 * 这几种都是实测会遇到的。解析不出来宁可返回空表（只用原问题），不要赌半个变体。
 */
class LlmQueryRewriterTest {

    @Test
    @DisplayName("从代码块包裹的输出里抠出数组")
    void parsesFencedArray() {
        String content = "好的，改写如下：\n```json\n[\"设备故障停机上报流程\", \"设备停机处理步骤\"]\n```\n以上。";

        List<String> variants = LlmQueryRewriter.parse(content, "停机了怎么办");

        assertEquals(List.of("设备故障停机上报流程", "设备停机处理步骤"), variants);
    }

    @Test
    @DisplayName("过滤空串、重复项与原问题本身；最多保留 MAX_VARIANTS 条")
    void filtersBlanksDuplicatesAndOriginal() {
        String content = "[\"原问题\", \"\", \"A\", \"A\", \"B\", \"C\", \"D\"]";

        List<String> variants = LlmQueryRewriter.parse(content, "原问题");

        assertEquals(List.of("A", "B", "C"), variants);
        assertEquals(LlmQueryRewriter.MAX_VARIANTS, variants.size());
    }

    @Test
    @DisplayName("没有数组/不是 JSON：返回空表而不是抛错（调用方只用原问题）")
    void toleratesNonJson() {
        assertTrue(LlmQueryRewriter.parse("我无法完成这个任务。", "q").isEmpty());
        assertTrue(LlmQueryRewriter.parse("", "q").isEmpty());
        assertTrue(LlmQueryRewriter.parse(null, "q").isEmpty());
        assertTrue(LlmQueryRewriter.parse("{\"a\": 1}", "q").isEmpty(), "对象不是数组");
    }

    @Test
    @DisplayName("数组元素里的方括号不影响配平（跳过字符串内部）")
    void bracesInsideStringsDoNotBreakExtraction() {
        String content = "说明{\"note\":\"引用 [1] 编号\"} 结果：[\"带[括号]的改写\"]";

        List<String> variants = LlmQueryRewriter.parse(content, "q");

        assertEquals(List.of("带[括号]的改写"), variants);
    }
}
