package com.hykj.assistant.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hykj.assistant.retrieval.Evidence;
import com.hykj.assistant.retrieval.RetrievalDiagnostics;
import com.hykj.assistant.retrieval.RetrievalResult;

/**
 * 这是拦错答案的闸门：证据不足、模型截断、引用编造、没有引用，四类都必须拒答。
 * 每一条都对应账本里的一个原因码，拒答率才能被 T11 统计和校准。
 */
class AnswerComposerTest {

    @Test
    @DisplayName("证据不足：直接拒答，且根本没有调用模型")
    void insufficientEvidenceRefusesWithoutCallingModel() {
        RetrievalResult retrieval = retrieval(true, evidence(1, "QM-ZD-2026-003", "V3.2"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, null, meta(false));

        assertTrue(answer.refused());
        assertEquals(AnswerComposer.RULE_INSUFFICIENT, answer.diagnostics().refusalRule());
        assertEquals(List.of(), answer.citations());
        assertFalse(answer.diagnostics().modelCalled());
        assertTrue(answer.answer().contains("无法可靠回答"), answer.answer());
        assertTrue(answer.answer().contains("相关性偏低"), "检索给的原因要带给用户：" + answer.answer());
    }

    @Test
    @DisplayName("引用合法：放行，并把编号翻成可定位的来源")
    void answerWithValidCitationPasses() {
        RetrievalResult retrieval = retrieval(false, evidence(1, "QM-ZD-2026-003", "V3.2"),
                evidence(2, "QM-ZD-2026-011", "V1.4"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "住宿标准是 500 元 [1]，交通费另算 [2]。", meta(true));

        assertFalse(answer.refused());
        assertNull(answer.diagnostics().refusalRule());
        assertEquals(List.of(1, 2), answer.citations().stream().map(GeneratedAnswer.Citation::rank).toList());
        assertEquals("QM-ZD-2026-003", answer.citations().get(0).docNo());
        assertEquals("V3.2", answer.citations().get(0).version());
        assertEquals("chunk-1", answer.citations().get(0).chunkId());
        assertEquals(2, answer.diagnostics().citedCount());
    }

    @Test
    @DisplayName("引用越界：答案再通顺也不给看，并把越界编号记进账本")
    void outOfRangeCitationIsRefused() {
        RetrievalResult retrieval = retrieval(false, evidence(1, "A-1", "V1.0"), evidence(2, "A-2", "V1.0"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "住宿标准是 500 元 [3]。", meta(true));

        assertTrue(answer.refused());
        assertEquals(AnswerComposer.RULE_OUT_OF_RANGE, answer.diagnostics().refusalRule());
        assertEquals(List.of(), answer.citations());
        assertEquals(List.of(3), answer.diagnostics().outOfRangeCitations());
        assertFalse(answer.answer().contains("500"), "编造来源的原文不得回传给用户：" + answer.answer());
    }

    @Test
    @DisplayName("一条引用都没有：按无法回答处理，保留模型自己的说法")
    void answerWithoutCitationIsRefused() {
        RetrievalResult retrieval = retrieval(false, evidence(1, "A-1", "V1.0"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "知识库中没有相关内容，无法回答。", meta(true));

        assertTrue(answer.refused());
        assertEquals(AnswerComposer.RULE_NO_CITATION, answer.diagnostics().refusalRule());
        assertEquals("知识库中没有相关内容，无法回答。", answer.answer());
    }

    @Test
    @DisplayName("模型返回空内容：拒答而不是给一个空答案")
    void emptyModelOutputIsRefused() {
        RetrievalResult retrieval = retrieval(false, evidence(1, "A-1", "V1.0"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "   ", meta(true));

        assertTrue(answer.refused());
        assertEquals(AnswerComposer.RULE_EMPTY_OUTPUT, answer.diagnostics().refusalRule());
    }

    @Test
    @DisplayName("被长度限制截断：半句话不给过，防止丢掉版本限定语")
    void truncatedOutputIsRefused() {
        RetrievalResult retrieval = retrieval(false, evidence(1, "A-1", "V1.0"));
        AnswerComposer.Meta truncated = new AnswerComposer.Meta("对话模型", true, true, 100, 1200, 900L);

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "住宿标准是 500 元 [1]，另", truncated);

        assertTrue(answer.refused());
        assertEquals(AnswerComposer.RULE_TRUNCATED, answer.diagnostics().refusalRule());
        assertTrue(answer.diagnostics().truncated());
    }

    @Test
    @DisplayName("全角引用标记同样算数：中文模型常见的输出习惯")
    void fullWidthCitationIsAccepted() {
        RetrievalResult retrieval = retrieval(false, evidence(1, "A-1", "V1.0"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "住宿标准是 500 元【1】。", meta(true));

        assertFalse(answer.refused());
        assertEquals(List.of(1), answer.citations().stream().map(GeneratedAnswer.Citation::rank).toList());
    }

    @Test
    @DisplayName("答案放行时提示也要带出来：被权限挡下的命中不能静默消失")
    void carriesRetrievalNoticeWhenAnswerIsServed() {
        RetrievalResult retrieval = retrieval(1, "另有 1 条更相关的命中你无权访问，请联系管理员开通",
                evidence(1, "QM-ZD-2026-011", "V1.4"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "维修作业须断电挂牌 [1]。", meta(true));

        assertFalse(answer.refused());
        assertTrue(answer.notice().contains("无权访问"), answer.notice());
    }

    @Test
    @DisplayName("只是没凑满 topN 不算异常：这种提示不进用户视野，免得让人以为出了问题")
    void shortfallNoticeIsNotShownToUser() {
        RetrievalResult retrieval = retrieval(false, "仅找到 1 份相关文档，少于请求的 5 份",
                evidence(1, "QM-ZD-2026-011", "V1.4"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "维修作业须断电挂牌 [1]。", meta(true));

        assertFalse(answer.refused());
        assertNull(answer.notice());
    }

    @Test
    @DisplayName("拒答时提示已并入正文，notice 保持为空，避免同一句话出现两遍")
    void refusalMergesNoticeIntoAnswerText() {
        RetrievalResult retrieval = retrieval(true, "命中内容相关性偏低",
                evidence(1, "QM-ZD-2026-011", "V1.4"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, null, meta(false));

        assertTrue(answer.refused());
        assertNull(answer.notice());
        assertTrue(answer.answer().contains("命中内容相关性偏低"), answer.answer());
    }

    @Test
    @DisplayName("证据随答案一起返回：前端才能'点引用跳原文'，不必再检索一次")
    void carriesEvidencesForCitationJump() {
        RetrievalResult retrieval = retrieval(false, evidence(1, "A-1", "V1.0"), evidence(2, "A-2", "V1.0"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, "内容 [2]。", meta(true));

        assertEquals(2, answer.evidences().size(), "喂给模型的证据要原样带出来，前端才能高亮原文片段");
        assertEquals("chunk-2", answer.evidences().get(1).fragments().get(0).chunkId());
        assertEquals("chunk-2", answer.citations().get(0).chunkId(), "引用与证据要对得上同一个 chunkId");
    }

    @Test
    @DisplayName("拒答也带着证据：用户至少能看到'检索到了什么'，而不是一片空白")
    void refusalStillCarriesEvidences() {
        RetrievalResult retrieval = retrieval(true, "检索到的证据相关性偏低", evidence(1, "A-1", "V1.0"));

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, null, meta(false));

        assertTrue(answer.refused());
        assertEquals(1, answer.evidences().size());
    }

    private static AnswerComposer.Meta meta(boolean called) {
        return new AnswerComposer.Meta(called ? "对话模型" : "", called, false, 120, 60, 1500L);
    }

    private static Evidence evidence(int rank, String docNo, String version) {
        return new Evidence(rank, rank, docNo, docNo + " 的标题", version, "现行", "内部公开", "财务部",
                docNo + "_" + version + ".pdf", 0.8,
                List.of(new Evidence.Fragment("chunk-" + rank, "片段正文 " + rank, 0.8, 0.2, 12)));
    }

    private static RetrievalResult retrieval(boolean insufficient, Evidence... evidences) {
        return retrieval(insufficient,
                insufficient ? "检索到的证据相关性偏低（最高分 0.33，低于作答阈值 0.45）" : null, null, 0,
                evidences);
    }

    private static RetrievalResult retrieval(boolean insufficient, String hint, Evidence... evidences) {
        return retrieval(insufficient, hint, null, 0, evidences);
    }

    private static RetrievalResult retrieval(int droppedUnauthorized, String hint, Evidence... evidences) {
        return retrieval(false, null, hint, droppedUnauthorized, evidences);
    }

    private static RetrievalResult retrieval(boolean insufficient, String hint, String accessNotice,
                                             int droppedUnauthorized, Evidence... evidences) {
        return new RetrievalResult(List.of(evidences), insufficient, hint, accessNotice,
                new RetrievalDiagnostics("user=test roles=[viewer] dept=(无) sql=exists(...)",
                        5, 0.25, 0.45, 0.35, 15, 5, 0, 0, 0.0, droppedUnauthorized, 0, 0, 0,
                        evidences.length, evidences.length, 0.8, false, 12L, false, 0d));
    }
}
