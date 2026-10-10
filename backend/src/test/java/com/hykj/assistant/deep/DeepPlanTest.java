package com.hykj.assistant.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 拆解解析的边界：模型输出的形状不可控，但"能不能用"必须可控。
 * 这几条断言就是"模型乱答时用户会看到什么"的答案。
 */
class DeepPlanTest {

    private static final String QUESTION = "GA-75 保养周期分几档，最长一档做什么";

    @Test
    @DisplayName("带代码块与前后废话的输出照样能解析")
    void parsesJsonInsideNoise() {
        String modelText = """
                好的，我先分析一下。
                ```json
                {"needClarification": false, "subQuestions": [
                  {"question": "GA-75 保养周期分几档", "why": "先问档数"},
                  {"question": "GA-75 最长一档保养做什么", "why": "再问内容"}]}
                ```
                以上是我的判断。""";

        DeepPlan.Plan plan = DeepPlan.parse(modelText, QUESTION, 4);

        assertFalse(plan.needClarification());
        assertEquals(2, plan.subQuestions().size());
        assertEquals("Q1", plan.subQuestions().get(0).id());
        assertEquals("GA-75 保养周期分几档", plan.subQuestions().get(0).question());
        assertEquals("再问内容", plan.subQuestions().get(1).why());
    }

    @Test
    @DisplayName("子问题重复只保留一条：同一个问题查两遍等于把预算花两遍")
    void deduplicatesSubQuestions() {
        String modelText = """
                {"subQuestions": [{"question": "GA-75保养周期分几档"}, {"question": "GA-75 保养周期，分几档？"}]}""";

        DeepPlan.Plan plan = DeepPlan.parse(modelText, QUESTION, 4);

        assertEquals(1, plan.subQuestions().size());
    }

    @Test
    @DisplayName("超出上限的子问题被丢弃而不是报错：多拆只会让成本翻倍")
    void truncatesToMax() {
        String modelText = """
                {"subQuestions": [{"question": "A"}, {"question": "B"}, {"question": "C"}, {"question": "D"}]}""";

        assertEquals(2, DeepPlan.parse(modelText, QUESTION, 2).subQuestions().size());
    }

    @Test
    @DisplayName("解析不出来就退化成单子问题=原问题，而不是让整次问答失败")
    void fallsBackOnGarbage() {
        for (String garbage : new String[] {"我看不出这个问题想问了什么", "{}", "[]", "{不是 JSON}"}) {
            DeepPlan.Plan plan = DeepPlan.parse(garbage, QUESTION, 4);
            assertEquals(1, plan.subQuestions().size(), "输入：" + garbage);
            assertEquals(QUESTION, plan.subQuestions().get(0).question());
            assertFalse(plan.needClarification());
        }
    }

    @Test
    @DisplayName("模型认为要澄清时，把追问话术原样带下去（要不要反问由查完之后决定）")
    void keepsClarifyQuestion() {
        String modelText = """
                {"needClarification": true, "clarifyQuestion": "你问的是哪个型号的保养周期？",
                 "subQuestions": [{"question": "保养周期分几档"}]}""";

        DeepPlan.Plan plan = DeepPlan.parse(modelText, QUESTION, 4);

        assertTrue(plan.needClarification());
        assertEquals("你问的是哪个型号的保养周期？", plan.clarifyQuestion());
    }

    @Test
    @DisplayName("说要澄清却一条子问题都没给：仍按原问题查一轮，别把答得上的问题变成一句反问")
    void clarifyWithoutSubQuestionsStillSearches() {
        DeepPlan.Plan plan = DeepPlan.parse(
                "{\"needClarification\": true, \"clarifyQuestion\": \"你说的是哪台设备？\"}", QUESTION, 4);

        assertTrue(plan.needClarification());
        assertEquals("你说的是哪台设备？", plan.clarifyQuestion());
        assertEquals(1, plan.subQuestions().size());
        assertEquals(QUESTION, plan.subQuestions().get(0).question());
        assertFalse(plan.empty());
    }

    @Test
    @DisplayName("说了要澄清却没给话术时用兜底话术，不能问一句空话")
    void clarifyWithoutTextUsesDefault() {
        DeepPlan.Plan plan = DeepPlan.parse("{\"needClarification\": true}", QUESTION, 4);

        assertTrue(plan.needClarification());
        assertEquals(DeepPlan.DEFAULT_CLARIFY, plan.clarifyQuestion());
    }

    @Test
    @DisplayName("子问题允许是纯字符串数组，模型经常这么写")
    void acceptsPlainStringArray() {
        DeepPlan.Plan plan = DeepPlan.parse("{\"subQuestions\": [\"第一问\", \"\"]}", QUESTION, 4);

        assertEquals(1, plan.subQuestions().size());
        assertEquals("第一问", plan.subQuestions().get(0).question());
        assertNull(plan.clarifyQuestion());
    }
}
