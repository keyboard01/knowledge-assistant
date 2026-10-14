package io.github.keyboard01.assistant.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.keyboard01.assistant.observe.EvalModels.Case;
import io.github.keyboard01.assistant.observe.EvalModels.CaseResult;
import io.github.keyboard01.assistant.observe.EvalModels.Metrics;

/**
 * 评测判定与指标（纯函数）。
 *
 * <p>这里坏起来最危险的一种方式是"永远通过"：命中判定写反、泄露不计数、分母算错，
 * 指标依然每天都是绿色的，回归门也就白设了。所以每条断言都盯着"什么情况下必须红"。</p>
 */
class EvalMetricsTest {

    private static Case item(String id, String outcome, List<String> allowed, List<String> mention,
                             boolean permission, List<String> forbidden, List<String> noLeak) {
        return new Case(id, "policy", "admin", "问题" + id, "multi-hop", outcome, permission, allowed, mention,
                forbidden, noLeak);
    }

    private static Case answered(List<String> allowed, List<String> mention) {
        return item("Q1", "answered", allowed, mention, false, List.of(), List.of());
    }

    @Test
    @DisplayName("full 模式：引到期望文档且含关键事实 = 通过")
    void fullModePasses() {
        CaseResult result = EvalMetrics.judge(answered(List.of("DOC-A"), List.of("500")), "full",
                List.of("DOC-A", "DOC-B"), false, List.of("DOC-A"), "住宿标准是 500 元 [1]。", 0.8, 7L, 1200);
        assertTrue(result.ok(), result.failureReason());
        assertEquals("answered", result.actualOutcome());
        assertTrue(result.hit());
        assertEquals(1, result.docRank());
        assertTrue(result.citationOk());
    }

    @Test
    @DisplayName("full 模式：少了关键事实、没引用期望文档、本该作答却拒答都要判失败")
    void fullModeCatchesEachFailure() {
        CaseResult missingFact = EvalMetrics.judge(answered(List.of("DOC-A"), List.of("500", "V3.2")), "full",
                List.of("DOC-A"), false, List.of("DOC-A"), "住宿标准是 500 元 [1]。", 0.8, 1L, 100);
        assertFalse(missingFact.ok());
        assertTrue(missingFact.failureReason().contains("V3.2"), missingFact.failureReason());

        CaseResult wrongDoc = EvalMetrics.judge(answered(List.of("DOC-A"), List.of()), "full",
                List.of("DOC-B"), false, List.of("DOC-B"), "另一份文档的说法是……[1]", 0.8, 1L, 100);
        assertFalse(wrongDoc.ok());
        assertTrue(wrongDoc.failureReason().contains("期望文档"), wrongDoc.failureReason());
        assertFalse(wrongDoc.hit());

        CaseResult refusedButShouldAnswer = EvalMetrics.judge(answered(List.of("DOC-A"), List.of()), "full",
                List.of(), true, List.of(), "知识库里没有", 0.2, 1L, 100);
        assertFalse(refusedButShouldAnswer.ok());
        assertTrue(refusedButShouldAnswer.failureReason().contains("本该作答"), refusedButShouldAnswer.failureReason());
    }

    @Test
    @DisplayName("泄露：被挡文档出现在证据或引用里一律失败，且计入 leaked")
    void leakingForbiddenDocFails() {
        Case permission = item("Q9", "refused", List.of(), List.of(), true, List.of("DOC-SECRET"), List.of());
        CaseResult result = EvalMetrics.judge(permission, "full", List.of("DOC-SECRET"), false,
                List.of("DOC-SECRET"), "秘密文档说……[1]", 0.9, 3L, 100);
        assertFalse(result.ok());
        assertTrue(result.leaked());
        assertTrue(result.failureReason().contains("不该引用"), result.failureReason());
    }

    @Test
    @DisplayName("权限题：被域级门直接挡下（denied）算通过——那正是期望行为")
    void permissionCaseExpectsDenial() {
        Case permission = item("Q9", "refused", List.of(), List.of(), true, List.of("DOC-SECRET"), List.of());
        CaseResult denied = new CaseResult("Q9", "policy", "demo", "问题Q9", null, "refused", "denied", true,
                null, false, null, null, false, 0, null, "", "", 10);
        Metrics metrics = EvalMetrics.summarize(List.of(denied), 0, 0, null);
        assertEquals(1, metrics.passed());
        assertEquals(0, metrics.leakCount());
    }

    @Test
    @DisplayName("retrieval 模式：只判期望文档有没有进证据，不判答案文字")
    void retrievalModeOnlyJudgesHits() {
        CaseResult hit = EvalMetrics.judge(answered(List.of("DOC-A"), List.of("500", "V3.2")), "retrieval",
                List.of("DOC-B", "DOC-A"), false, List.of(), null, 0.4, null, 50);
        assertTrue(hit.ok(), "retrieval 模式不该因为'没有答案文字'判失败：" + hit.failureReason());
        assertTrue(hit.hit());
        assertEquals(2, hit.docRank());

        CaseResult miss = EvalMetrics.judge(answered(List.of("DOC-A"), List.of()), "retrieval",
                List.of("DOC-B"), false, List.of(), null, 0.4, null, 50);
        assertFalse(miss.ok());
        assertTrue(miss.failureReason().contains("期望文档没进证据"), miss.failureReason());
        assertNull(miss.citationOk());
    }

    @Test
    @DisplayName("指标：hit@k、MRR、拒答准确率、引用正确率各自的分母要对")
    void metricsUseCorrectDenominators() {
        CaseResult hitFirst = EvalMetrics.judge(answered(List.of("DOC-A"), List.of()), "full",
                List.of("DOC-A"), false, List.of("DOC-A"), "答案 [1]", 0.8, 1L, 100);
        CaseResult hitSecond = EvalMetrics.judge(item("Q2", "answered", List.of("DOC-B"), List.of(), false,
                List.of(), List.of()), "full", List.of("DOC-C", "DOC-B"), false, List.of("DOC-B"), "答案 [1]",
                0.7, 2L, 300);
        CaseResult missed = EvalMetrics.judge(item("Q3", "answered", List.of("DOC-D"), List.of(), false,
                List.of(), List.of()), "full", List.of("DOC-C"), false, List.of("DOC-C"), "答案 [1]", 0.6, 3L,
                200);
        // 没有期望文档的题（如"库里没有"）不参与 hit@k / MRR
        CaseResult refusal = EvalMetrics.judge(item("Q4", "refused", List.of(), List.of(), false, List.of(),
                List.of()), "full", List.of(), true, List.of(), "知识库中没有", 0.1, 4L, 100);

        Metrics metrics = EvalMetrics.summarize(List.of(hitFirst, hitSecond, missed, refusal), 10, 20, null);
        assertEquals(4, metrics.passed() + metrics.failed());
        assertEquals(3, metrics.passed(), "只有 missed 该失败");
        assertEquals(1, metrics.failed());
        // hit@k 分母 = 3（有期望文档的题），命中 2
        assertEquals(0.6667, metrics.hitAtK(), 0.0001);
        // MRR = (1/1 + 1/2 + 0)/3
        assertEquals(0.5, metrics.mrr(), 0.0001);
        // 拒答准确率分母 = 4（每题都有明确的期望结果），全对
        assertEquals(1d, metrics.refusalAccuracy(), 0.0001);
        // 引用正确率只看作答的题：3 题里 2 题（missed 也引了 DOC-C，但与期望不符 → 不算）
        assertEquals(0.6667, metrics.citationAccuracy(), 0.0001);
        assertEquals(0, metrics.leakCount());
        assertEquals((100 + 300 + 200 + 100) / 4, metrics.avgElapsedMs());
    }

    @Test
    @DisplayName("空题集：指标为 0 而不是崩")
    void emptySetIsSafe() {
        Metrics metrics = EvalMetrics.summarize(List.of(), 0, 0, null);
        assertEquals(0d, metrics.hitAtK());
        assertEquals(0d, metrics.mrr());
        assertNull(metrics.citationAccuracy());
        assertNull(metrics.refusalAccuracy());
        assertNull(metrics.avgBestScore());
    }
}
