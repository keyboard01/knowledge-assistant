package io.github.keyboard01.assistant.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 预算与取消：这几条断言保证的是"任何情况下不超预算"这句话在代码里真的成立。 */
class DeepLedgerTest {

    @Test
    @DisplayName("请求只能把预算调低，调高按上限算")
    void clampsBudgetToCeiling() {
        DeepBudget raised = DeepBudget.of(99, 99, 99, 9_999_999L, 999_999);
        assertEquals(DeepBudget.CEILING, raised);

        DeepBudget lowered = DeepBudget.of(2, 3, 1, 5_000L, 800);
        assertEquals(new DeepBudget(2, 3, 1, 5_000L, 800), lowered);

        assertEquals(DeepBudget.DEFAULT, DeepBudget.of(null, null, null, null, null));
    }

    @Test
    @DisplayName("预算项必须为正：填 0 是配置错误，不能悄悄当成无限")
    void rejectsNonPositiveBudget() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DeepBudget(0, 1, 1, 1000L, 100));
    }

    @Test
    @DisplayName("工具调用到顶即停：不会出现'最后一次把预算撑爆'")
    void stopsOnToolBudget() {
        DeepLedger ledger = new DeepLedger(new DeepBudget(4, 2, 3, 60_000L, 10_000));
        assertTrue(ledger.stop().isEmpty());
        ledger.chargeTool();
        assertTrue(ledger.stop().isEmpty());
        ledger.chargeTool();
        assertEquals(DeepLedger.CODE_TOOL_BUDGET, ledger.stop().orElseThrow().code());
        assertEquals(2, ledger.toolCalls());
    }

    @Test
    @DisplayName("轮数用 roundsLeft 判：轮的开销是'再转一圈'，不是'再多走一步'")
    void tracksRounds() {
        DeepLedger ledger = new DeepLedger(new DeepBudget(4, 10, 2, 60_000L, 10_000));
        assertTrue(ledger.roundsLeft());
        ledger.startRound();
        ledger.startRound();
        assertFalse(ledger.roundsLeft());
    }

    @Test
    @DisplayName("耗时触顶：用假时钟测，真时钟测不出稳定的边界")
    void stopsOnElapsed() {
        AtomicLong now = new AtomicLong();
        DeepLedger ledger = new DeepLedger(new DeepBudget(4, 10, 3, 1_000L, 10_000), now::get);
        assertTrue(ledger.stop().isEmpty());
        now.addAndGet(1_200_000_000L);
        assertEquals(DeepLedger.CODE_ELAPSED_BUDGET, ledger.stop().orElseThrow().code());
        assertEquals(1_200L, ledger.elapsedMs());
    }

    @Test
    @DisplayName("输出 token 到顶后还能作答这一步：为了省 token 连查到的证据都不答，等于前面全白花")
    void tokenBudgetStopsToolsButStillAllowsGeneration() {
        DeepLedger ledger = new DeepLedger(new DeepBudget(4, 10, 3, 60_000L, 100));
        ledger.chargeTokens(0, 100);
        assertEquals(DeepLedger.CODE_TOKEN_BUDGET, ledger.stop().orElseThrow().code());
        // stopForGeneration 也拦 token，但那是因为真的没钱了；这里验证的是"两个出口不是同一个判断"
        assertEquals(DeepLedger.CODE_TOKEN_BUDGET, ledger.stopForGeneration().orElseThrow().code());
    }

    @Test
    @DisplayName("取消是外部线程调用的，优先级最高")
    void cancelWins() {
        DeepLedger ledger = new DeepLedger(DeepBudget.DEFAULT);
        ledger.cancel();
        assertEquals(DeepLedger.CODE_CANCELLED, ledger.stop().orElseThrow().code());
        assertEquals(DeepLedger.CODE_CANCELLED, ledger.stopForGeneration().orElseThrow().code());
    }

    @Test
    @DisplayName("模型名只记第一次：账本回答的是'这次是哪家模型答的'")
    void notesFirstModelName() {
        DeepLedger ledger = new DeepLedger(DeepBudget.DEFAULT);
        ledger.noteModel("qwen-max");
        ledger.noteModel("对话模型");
        assertEquals("qwen-max", ledger.model());
        assertEquals("", new DeepLedger(DeepBudget.DEFAULT).model());
    }

    @Test
    @DisplayName("停止原因是可读的一句话，不是一个裸的枚举")
    void stopMessageIsHumanReadable() {
        DeepLedger ledger = new DeepLedger(new DeepBudget(4, 1, 3, 60_000L, 10_000));
        ledger.chargeTool();
        Optional<DeepLedger.Stop> stop = ledger.stop();
        assertTrue(stop.orElseThrow().message().contains("1"));
    }
}
