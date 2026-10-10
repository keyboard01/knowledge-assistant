package io.github.keyboard01.assistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多查询融合与单路 RRF 必须给出同一结果（否则"改写关闭"与"改写开启但只回退到原问题"会分叉），
 * 且多查询要能把"在多个变体里都靠前"的片段顶上来——这是改写的价值所在。
 */
class MultiQueryFusionTest {

    private static final int K = RrfFusion.DEFAULT_K;

    @Test
    @DisplayName("单路输入与 RrfFusion.fuse 逐位等价：改写关闭/回退路径不会因换实现而变序")
    void singleListMatchesRrfFusion() {
        List<ChunkRow> vector = List.of(row(1L, 0.9), row(2L, 0.8));
        List<ChunkRow> keyword = List.of(row(2L, 0.5), row(3L, 0.4));

        List<FusedChunk> single = RrfFusion.fuse(vector, keyword, K, 0);
        List<FusedChunk> multi = MultiQueryFusion.fuse(List.of(vector), List.of(keyword), K, 0);

        assertEquals(single.size(), multi.size());
        for (int i = 0; i < single.size(); i++) {
            assertEquals(single.get(i).chunk().chunkId(), multi.get(i).chunk().chunkId());
            assertEquals(single.get(i).fusedScore(), multi.get(i).fusedScore(), 1e-12);
            assertEquals(single.get(i).vectorScore(), multi.get(i).vectorScore(), 1e-12);
            assertEquals(single.get(i).keywordScore(), multi.get(i).keywordScore(), 1e-12);
            assertEquals(single.get(i).vectorRank(), multi.get(i).vectorRank());
            assertEquals(single.get(i).keywordRank(), multi.get(i).keywordRank());
        }
    }

    @Test
    @DisplayName("多查询共识：在两个变体里都排第一的片段，胜过只在其中一个排第一的")
    void consensusWinsAcrossQueries() {
        List<ChunkRow> queryA = List.of(row(1L, 0.90), row(2L, 0.80));
        List<ChunkRow> queryB = List.of(row(1L, 0.85), row(3L, 0.70));

        List<FusedChunk> fused = MultiQueryFusion.fuse(List.of(queryA, queryB),
                List.of(List.of(), List.of()), K, 0);

        assertEquals(1L, fused.get(0).chunk().chunkId(), "两路都第一的片段应排最前");
        assertEquals(2.0 / (K + 1), fused.get(0).fusedScore(), 1e-12, "名次分跨查询累加");
    }

    @Test
    @DisplayName("精确词半票只加一次：多个关键词通道都强命中，也只加一份，不随变体数量膨胀")
    void strongKeywordBonusCountedOnce() {
        List<ChunkRow> strong = List.of(row(1L, 0.5));

        List<FusedChunk> fused = MultiQueryFusion.fuse(List.of(), List.of(strong, strong), K, 0);

        assertEquals(2.0 / (K + 1) + RrfFusion.STRONG_KEYWORD_BONUS / (K + 1),
                fused.get(0).fusedScore(), 1e-12);
    }

    @Test
    @DisplayName("limit 截断；limit <= 0 不截断")
    void limitTruncates() {
        List<List<ChunkRow>> vectorLists = List.of(List.of(row(1L, 0.9), row(2L, 0.8)));
        List<List<ChunkRow>> keywordLists = List.of(List.<ChunkRow>of());

        assertEquals(1, MultiQueryFusion.fuse(vectorLists, keywordLists, K, 1).size());
        assertEquals(2, MultiQueryFusion.fuse(vectorLists, keywordLists, K, -1).size());
    }

    private static ChunkRow row(long chunkId, double score) {
        return new ChunkRow(chunkId, 1L, 0, "正文" + chunkId, null, null, null, "PARAGRAPH",
                "D-" + chunkId, "标题", "V1.0", "现行", "内部公开", "财务部", "D.pdf", score);
    }
}
