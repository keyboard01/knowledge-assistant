package com.hykj.assistant.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hykj.assistant.deep.DeepEvidenceMerger.Merged;
import com.hykj.assistant.deep.DeepEvidenceMerger.Round;
import com.hykj.assistant.deep.DeepModels.SubQuestion;
import com.hykj.assistant.generation.AnswerComposer;
import com.hykj.assistant.generation.LanguageModel;
import com.hykj.assistant.retrieval.Evidence;

/**
 * S8 分步作答：既要"长答案不再整段被拒"，也要"引用校验一个字都不放松"。
 * 这两件事同时在一个类里，所以两边都要有断言——只顾一头就会把另一头悄悄改坏。
 */
class StepwiseAnswererTest {

    private static final DeepBudget BUDGET = new DeepBudget(4, 10, 3, 60_000L, 10_000);

    @Test
    @DisplayName("单一子问题：不加①标题，正文就是答案，引用照样翻译成文档")
    void answersSingleSection() throws Exception {
        FakeModel model = new FakeModel().then(answer("GA-75 分四档，最长一档 2000h 做大保养 [1]。"));
        Merged merged = DeepEvidenceMerger.merge(
                List.of(new Round("Q1", List.of(document(8, 0.7, "表行：2000h 大保养", "c-9", 14)))));

        StepwiseAnswerer.Result result = answerer(model)
                .answer(plan("GA-75 保养周期分几档"), merged, new DeepLedger(BUDGET), null, null);

        assertEquals(1, result.sections().size());
        assertEquals(1, result.answeredSections());
        assertEquals("GA-75 分四档，最长一档 2000h 做大保养 [1]。", result.joined());
        assertEquals(1, result.citations().size());
        assertEquals(8, result.citations().get(0).documentId());
        assertEquals("c-9", result.citations().get(0).chunkId());
    }

    @Test
    @DisplayName("两个子问题分两段生成：每段的提示词只带自己那一份证据")
    void splitsBySubQuestion() throws Exception {
        FakeModel model = new FakeModel().then(answer("住宿标准是 500 元 [1]。")).then(answer("更换周期是 500h [2]。"));
        Merged merged = DeepEvidenceMerger.merge(List.of(
                new Round("Q1", List.of(document(5, 0.9, "住宿标准 500 元", "c-1", 1))),
                new Round("Q2", List.of(document(8, 0.5, "更换周期 500h", "c-2", 2)))));

        StepwiseAnswerer.Result result = answerer(model)
                .answer(plan("出差住宿标准是多少", "滤芯更换周期是多少"), merged, new DeepLedger(BUDGET), null, null);

        assertEquals(2, result.answeredSections());
        assertTrue(model.users.get(0).contains("住宿标准 500 元"));
        assertFalse(model.users.get(0).contains("更换周期 500h"), "第一段的提示词里不该出现第二份文档");
        assertTrue(result.joined().startsWith("①"));
        assertTrue(result.joined().contains("②"));
        // 两段的引用都被收下，且编号仍是全局编号：前端点 [2] 必须跳到第二份文档
        assertEquals(2, result.citations().size());
        assertEquals(5, result.citations().get(0).documentId());
        assertEquals(8, result.citations().get(1).documentId());
    }

    @Test
    @DisplayName("被长度截断只重试一次，且第二次要求它收敛篇幅")
    void retriesOnceWhenTruncated() throws Exception {
        FakeModel model = new FakeModel()
                .then(new LanguageModel.Completion("第一段写到一半就断了……", "fake-model", 100, 900, true))
                .then(answer("要点：分四档，最长一档 2000h [1]。"));
        Merged merged = DeepEvidenceMerger.merge(
                List.of(new Round("Q1", List.of(document(8, 0.7, "表行：2000h 大保养", "c-9", 14)))));

        StepwiseAnswerer.Result result = answerer(model)
                .answer(plan("GA-75 保养周期分几档"), merged, new DeepLedger(BUDGET), null, null);

        assertEquals(2, result.modelCalls());
        assertEquals(1, result.answeredSections());
        assertTrue(model.systems.get(1).contains("截断"), "重试时要说清上次为什么失败");
        assertEquals("要点：分四档，最长一档 2000h [1]。", result.joined());
    }

    @Test
    @DisplayName("某一段没有证据：只声明这一段不足，其余照样答（P30 要的'先给一半'）")
    void keepsOtherSectionsWhenOneHasNoEvidence() throws Exception {
        FakeModel model = new FakeModel().then(answer("CNC850 的答案 [1]。"));
        Merged merged = DeepEvidenceMerger.merge(
                List.of(new Round("Q1", List.of(document(5, 0.9, "CNC850 规格", "c-1", 1)))));

        StepwiseAnswerer.Result result = answerer(model)
                .answer(plan("CNC850 规格是什么", "HYZ40 的规格是什么"), merged, new DeepLedger(BUDGET), null, null);

        assertEquals(2, result.sections().size());
        assertEquals(1, result.answeredSections());
        assertEquals(1, result.modelCalls(), "没有证据的那一段不该再花一次模型调用");
        assertFalse(result.sections().get(1).answered());
        assertEquals(AnswerComposer.RULE_INSUFFICIENT, result.sections().get(1).refusalRule());
        assertTrue(result.joined().contains("②"));
    }

    @Test
    @DisplayName("一条引用都没有：整段按拒答处理，没有出处的内容不接受")
    void refusesSectionWithoutCitation() throws Exception {
        FakeModel model = new FakeModel().then(answer("我觉得应该是四档。"));
        Merged merged = DeepEvidenceMerger.merge(
                List.of(new Round("Q1", List.of(document(8, 0.7, "表行", "c-9", 14)))));

        StepwiseAnswerer.Result result = answerer(model)
                .answer(plan("GA-75 保养周期分几档"), merged, new DeepLedger(BUDGET), null, null);

        assertEquals(0, result.answeredSections());
        assertEquals(AnswerComposer.RULE_NO_CITATION, result.sections().get(0).refusalRule());
    }

    @Test
    @DisplayName("引用越界（编造来源）同样拦下，不因为'深度模式'放宽")
    void refusesOutOfRangeCitation() throws Exception {
        FakeModel model = new FakeModel().then(answer("答案见 [7]。"));
        Merged merged = DeepEvidenceMerger.merge(
                List.of(new Round("Q1", List.of(document(8, 0.7, "表行", "c-9", 14)))));

        StepwiseAnswerer.Result result = answerer(model)
                .answer(plan("GA-75 保养周期分几档"), merged, new DeepLedger(BUDGET), null, null);

        assertEquals(0, result.answeredSections());
        assertEquals(AnswerComposer.RULE_OUT_OF_RANGE, result.sections().get(0).refusalRule());
    }

    @Test
    @DisplayName("取消后不再生成新的一段，但已经答出来的段留在结果里")
    void stopsGeneratingAfterCancel() throws Exception {
        FakeModel model = new FakeModel().then(answer("第一段答案 [1]。"));
        Merged merged = DeepEvidenceMerger.merge(List.of(
                new Round("Q1", List.of(document(5, 0.9, "第一份内容", "c-1", 1))),
                new Round("Q2", List.of(document(8, 0.5, "第二份内容", "c-2", 2)))));
        DeepLedger ledger = new DeepLedger(BUDGET);
        ledger.cancel();

        StepwiseAnswerer.Result result = answerer(model)
                .answer(plan("第一问", "第二问"), merged, ledger, null, null);

        assertEquals(0, result.modelCalls());
        assertEquals(0, result.answeredSections());
        assertTrue(result.joined().contains("本段未生成"));
    }

    private static StepwiseAnswerer answerer(LanguageModel model) {
        return new StepwiseAnswerer(model);
    }

    private static LanguageModel.Completion answer(String text) {
        return new LanguageModel.Completion(text, "fake-model", 100, 50, false);
    }

    private static Evidence document(long id, double score, String text, String chunkId, int chunkIndex) {
        return DeepFixtures.document(id, "DOC-" + id, "文档 " + id, score,
                DeepFixtures.fragment(chunkId, chunkIndex, text, score));
    }

    private static DeepPlan.Plan plan(String... questions) {
        List<SubQuestion> subQuestions = new ArrayList<>();
        for (int i = 0; i < questions.length; i++) {
            subQuestions.add(new SubQuestion("Q" + (i + 1), questions[i], "测试"));
        }
        return new DeepPlan.Plan(false, null, subQuestions);
    }

    /** 假模型：按顺序吐出预置回答，并把每次的提示词留下来给断言看。 */
    private static final class FakeModel implements LanguageModel {

        private final List<String> systems = new ArrayList<>();
        private final List<String> users = new ArrayList<>();
        private final List<Completion> answers = new ArrayList<>();
        private int index;

        FakeModel then(Completion completion) {
            answers.add(completion);
            return this;
        }

        @Override
        public Completion complete(String systemPrompt, String userPrompt) {
            systems.add(systemPrompt);
            users.add(userPrompt);
            return index < answers.size()
                    ? answers.get(index++)
                    : new Completion("兜底回答", "fake-model", 1, 1, false);
        }

        @Override
        public Completion streamComplete(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
            Completion completion = complete(systemPrompt, userPrompt);
            onDelta.accept(completion.content());
            return completion;
        }
    }
}
