package io.github.keyboard01.assistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 重排是"只改顺序、不改口径"的那一层，所以它必须满足两件事：
 * ①分数高的真的排前面（哪怕 RRF 名次靠后）；②分数打平/全部相同时逐位退化为原顺序。
 * 后者是 fail-open 的正确性基础——熔断时调用方压根不重排，这里保证"等价于不重排"也不会翻车。
 */
class RerankStageTest {

    @Test
    @DisplayName("按重排分重排：RRF 靠后的候选可以靠相关性分挤到最前")
    void reordersByRerankScore() {
        FusedChunk first = chunk(1L, 0.9, 0.9);   // RRF/向量都最强
        FusedChunk third = chunk(3L, 0.3, 0.3);   // 两路都弱
        List<RerankStage.Scored> ranked = RerankStage.apply(
                List.of(first, third), List.of(0.20, 0.95));

        assertEquals(3L, ranked.get(0).chunk().chunk().chunkId(), "重排分高的排前面");
        assertEquals(0.95, ranked.get(0).rerankScore(), 1e-12);
        assertEquals(1L, ranked.get(1).chunk().chunk().chunkId());
    }

    @Test
    @DisplayName("重排分打平：退化为 RRF 融合分、再向量分、再 chunkId，与不重排一致")
    void tieFallsBackToRrfOrder() {
        FusedChunk highFused = chunk(1L, 0.7, 0.5);
        FusedChunk lowFused = chunk(2L, 0.4, 0.9);

        List<RerankStage.Scored> ranked = RerankStage.apply(
                List.of(lowFused, highFused), List.of(0.5, 0.5));

        assertEquals(1L, ranked.get(0).chunk().chunk().chunkId(), "融合分高的排前面");
    }

    @Test
    @DisplayName("重排分与融合分全部相同：按 chunkId 定序，结果稳定可复现")
    void fullyTiedIsDeterministic() {
        List<RerankStage.Scored> ranked = RerankStage.apply(
                List.of(chunk(5L, 0.5, 0.5), chunk(2L, 0.5, 0.5)), List.of(0.5, 0.5));

        assertEquals(2L, ranked.get(0).chunk().chunk().chunkId());
        assertEquals(5L, ranked.get(1).chunk().chunk().chunkId());
    }

    @Test
    @DisplayName("分数条数与候选不一致：抛错（调用方据此 fail-open 退回原序）")
    void sizeMismatchThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RerankStage.apply(List.of(chunk(1L, 0.5, 0.5)), List.of(0.5, 0.6)));
    }

    @Test
    @DisplayName("某个候选缺重排分：抛错，不半信半疑地重排一半")
    void nullScoreThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RerankStage.apply(List.of(chunk(1L, 0.5, 0.5)), java.util.Arrays.asList((Double) null)));
    }

    @Test
    @DisplayName("空候选返回空，不抛错")
    void emptyIsEmpty() {
        assertTrue(RerankStage.apply(List.of(), List.of()).isEmpty());
    }

    private static FusedChunk chunk(long chunkId, double fusedScore, double vectorScore) {
        ChunkRow row = new ChunkRow(chunkId, 1L, 0, "正文" + chunkId, null, null, null, "PARAGRAPH",
                "D-" + chunkId, "标题", "V1.0", "现行", "内部公开", "财务部", vectorScore);
        return new FusedChunk(row, fusedScore, vectorScore, 0, 1, 0);
    }
}
