package io.github.keyboard01.assistant.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.keyboard01.assistant.generation.GeneratedAnswer;
import io.github.keyboard01.assistant.generation.GenerationDiagnostics;
import io.github.keyboard01.assistant.observe.ObservationModels.PriceSnapshot;

/**
 * 费用换算与单价解析。
 *
 * <p>这两件事的共同点是"错了不会报错"：单位写错就是静默小 1000 倍，
 * 单价填错就是把整次问答带崩。所以用单测把边界钉死。</p>
 */
class ObserveServiceTest {

    private static final BigDecimal TWO_YUAN = new BigDecimal("2");
    private static final BigDecimal EIGHT_YUAN = new BigDecimal("8");

    @Test
    @DisplayName("未配单价时费用为 null，不是 0——0 会被读成'免费'，那是相反的结论")
    void noPriceMeansNullCost() {
        assertNull(ObserveService.estimateCost(answer(2000, 500), PriceSnapshot.UNPRICED));
        assertFalse(PriceSnapshot.UNPRICED.priced());
    }

    @Test
    @DisplayName("单价按'元/千 token'换算")
    void convertsPerThousandTokens() {
        // 2000 / 1000 * 2 = 4；500 / 1000 * 8 = 4；合计 8 元
        assertEquals(new BigDecimal("8.000000"),
                ObserveService.estimateCost(answer(2000, 500), new PriceSnapshot(TWO_YUAN, EIGHT_YUAN, "test")));
    }

    @Test
    @DisplayName("单价为 0 是'免费'，与'不知道'分开：前者记 0，后者记 null")
    void zeroPriceIsFreeNotUnknown() {
        PriceSnapshot free = new PriceSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, "本地模型");
        assertTrue(free.priced());
        assertEquals(new BigDecimal("0.000000"), ObserveService.estimateCost(answer(2000, 500), free));
    }

    @Test
    @DisplayName("只配了输入单价等于没配：拆成两笔钱只算一笔，比不算更糟")
    void halfConfiguredPriceIsUnpriced() {
        assertFalse(new PriceSnapshot(TWO_YUAN, null, null).priced());
    }

    @Test
    @DisplayName("单价填错（非数字、负数）退化成没配，而不是让问答失败")
    void invalidPriceFallsBackToUnpriced() {
        assertNull(ObserveService.parsePrice("abc"));
        assertNull(ObserveService.parsePrice("-1"));
        assertNull(ObserveService.parsePrice("  "));
        assertNull(ObserveService.parsePrice(null));
    }

    @Test
    @DisplayName("单价容得下小数与空白")
    void acceptsDecimalsAndWhitespace() {
        assertEquals(new BigDecimal("0.5"), ObserveService.parsePrice(" 0.5 "));
        assertEquals(BigDecimal.ZERO, ObserveService.parsePrice("0"));
    }

    private static GeneratedAnswer answer(int promptTokens, int completionTokens) {
        return new GeneratedAnswer("答案", List.of(), false, null, null, List.of(),
                new GenerationDiagnostics("test-model", true, 1, 1, List.of(), promptTokens, completionTokens,
                        null, false, 120L));
    }
}
