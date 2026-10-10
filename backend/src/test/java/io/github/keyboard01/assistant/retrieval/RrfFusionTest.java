package io.github.keyboard01.assistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RRF 只看名次，所以两路分数不可比这件事在这里被彻底绕开；精确词加成是补 RRF 的一处短板。
 * 这两点是 T15 融合策略的核心，必须钉住。
 */
class RrfFusionTest {

    private static final int K = RrfFusion.DEFAULT_K;
    private static final double SINGLE = 1.0 / (K + 1);

    @Test
    @DisplayName("融合分只由名次决定：两路各自的第一名拿到完全相同的分数，与相似度数值无关")
    void fusedScoreDependsOnRankOnly() {
        List<ChunkRow> vector = List.of(row(1L, 0.99));
        List<ChunkRow> keyword = List.of(row(2L, 0.11));

        List<FusedChunk> fused = RrfFusion.fuse(vector, keyword, K, 0);

        assertEquals(2, fused.size());
        assertEquals(SINGLE, fused.get(0).fusedScore(), 1e-12);
        assertEquals(SINGLE, fused.get(1).fusedScore(), 1e-12);
        assertEquals(1L, fused.get(0).chunk().chunkId(), "同分时按向量分排序，0.99 在前");
    }

    @Test
    @DisplayName("精确词强命中拿半票加成：0.5 相似度 > 0.2 相似度")
    void strongKeywordGetsHalfBonus() {
        double strong = RrfFusion.fuse(List.of(), List.of(row(1L, 0.5)), K, 0).get(0).fusedScore();
        double weak = RrfFusion.fuse(List.of(), List.of(row(1L, 0.2)), K, 0).get(0).fusedScore();

        assertEquals(SINGLE + RrfFusion.STRONG_KEYWORD_BONUS / (K + 1), strong, 1e-12);
        assertEquals(SINGLE, weak, 1e-12);
        assertTrue(strong > weak);
    }

    @Test
    @DisplayName("两路都命中：融合分叠加，两路名次都保留下来供排障")
    void bothChannelsAccumulate() {
        FusedChunk hit = RrfFusion.fuse(List.of(row(7L, 0.8)), List.of(row(7L, 0.6)), K, 0).get(0);

        assertTrue(hit.hitByVector());
        assertTrue(hit.hitByKeyword());
        assertEquals(1, hit.vectorRank());
        assertEquals(1, hit.keywordRank());
        assertEquals(2.0 / (K + 1) + RrfFusion.STRONG_KEYWORD_BONUS / (K + 1), hit.fusedScore(), 1e-12);
    }

    @Test
    @DisplayName("融合会重排名次：两路各排第二的片段能挤掉只在一路排第一的（超采的理由）")
    void fusionCanPromoteADoubleSecond() {
        List<ChunkRow> vector = List.of(row(1L, 0.9), row(2L, 0.8));
        List<ChunkRow> keyword = List.of(row(2L, 0.5), row(3L, 0.4));

        List<FusedChunk> fused = RrfFusion.fuse(vector, keyword, K, 0);

        assertEquals(3, fused.size());
        assertEquals(2L, fused.get(0).chunk().chunkId(), "两路各第二的片段应当排到第一");
        assertEquals(3L, fused.get(1).chunk().chunkId());
        assertEquals(1L, fused.get(2).chunk().chunkId());
    }

    @Test
    @DisplayName("limit 截断；limit <= 0 表示不截断")
    void limitTruncates() {
        List<ChunkRow> vector = List.of(row(1L, 0.9), row(2L, 0.8), row(3L, 0.7));

        assertEquals(2, RrfFusion.fuse(vector, List.of(), K, 2).size());
        assertEquals(3, RrfFusion.fuse(vector, List.of(), K, -1).size());
    }

    @Test
    @DisplayName("只在一路出现的片段也有融合分，不会因为是单路命中而被丢掉")
    void singleChannelHitsSurvive() {
        List<FusedChunk> fused = RrfFusion.fuse(List.of(row(1L, 0.9)), List.of(row(2L, 0.5)), K, 0);

        assertEquals(2, fused.size());
        assertTrue(fused.stream().allMatch(item -> item.fusedScore() > 0));
    }

    private static ChunkRow row(long chunkId, double score) {
        return new ChunkRow(chunkId, 1L, 0, "正文" + chunkId, null, null, null, "PARAGRAPH",
                "D-1", "D-1 的标题", "V1.0", "现行", "内部公开", "财务部", "D-1_V1.0.pdf", score);
    }
}