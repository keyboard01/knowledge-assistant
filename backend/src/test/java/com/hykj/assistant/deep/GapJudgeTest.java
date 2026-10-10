package com.hykj.assistant.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S7 的三个出口：答、半答、拒答。三种处置完全不同，混成一个"答不了"就是这次要修的病。 */
class GapJudgeTest {

    private static final Optional<DeepLedger.Stop> NO_STOP = Optional.empty();
    private static final Optional<DeepLedger.Stop> BUDGET_STOP =
            Optional.of(new DeepLedger.Stop(DeepLedger.CODE_TOOL_BUDGET, "已达最大工具调用次数 10"));

    @Test
    @DisplayName("一条证据都没有：拒答，这是'知识库里没有'")
    void refusesWithoutEvidence() {
        GapJudge.Decision decision = GapJudge.decide(0, 2, null, NO_STOP, 0);
        assertEquals(GapJudge.Action.REFUSE, decision.action());
    }

    @Test
    @DisplayName("有子问题没覆盖：先答能答的，并说明缺哪部分（这就是 P30 的修法）")
    void answersPartially() {
        GapJudge.Decision decision = GapJudge.decide(3, 1, null, NO_STOP, 5);
        assertEquals(GapJudge.Action.ANSWER_PARTIAL, decision.action());
        assertTrue(decision.text().contains("1 个子问题"));
    }

    @Test
    @DisplayName("预算触顶是正常结束，但必须说出来：用户要知道答案可能不完整")
    void reportsBudgetStop() {
        GapJudge.Decision decision = GapJudge.decide(3, 0, null, BUDGET_STOP, 4);
        assertEquals(GapJudge.Action.ANSWER, decision.action());
        assertTrue(decision.text().contains("预算上限"));
    }

    @Test
    @DisplayName("无新增证据也是正常结束：它是设计文档点名的合法状态，不是异常")
    void answersWhenNoNewEvidence() {
        GapJudge.Decision decision = GapJudge.decide(3, 0, null, NO_STOP, 0);
        assertEquals(GapJudge.Action.ANSWER, decision.action());
        assertTrue(decision.text().contains("无新增证据"));
    }

    @Test
    @DisplayName("要澄清时先追问：不检索也不作答")
    void clarifies() {
        GapJudge.Decision decision = GapJudge.decide(0, 0, "你问的是哪个型号？", NO_STOP, 0);
        assertEquals(GapJudge.Action.CLARIFY, decision.action());
        assertEquals("你问的是哪个型号？", decision.text());
    }

    @Test
    @DisplayName("查到了证据就不澄清：模型判'缺限定'只是提示，能不能答要看查到了什么")
    void doesNotClarifyWhenEvidenceFound() {
        GapJudge.Decision decision = GapJudge.decide(3, 0, "你问的是哪个型号？", NO_STOP, 5);
        assertEquals(GapJudge.Action.ANSWER, decision.action());
        assertNull(decision.text());
    }

    @Test
    @DisplayName("一切正常时不多说废话")
    void staysQuietWhenCovered() {
        assertNull(GapJudge.decide(5, 0, null, NO_STOP, 3).text());
        assertEquals(GapJudge.Action.ANSWER, GapJudge.decide(5, 0, null, NO_STOP, 3).action());
    }
}
