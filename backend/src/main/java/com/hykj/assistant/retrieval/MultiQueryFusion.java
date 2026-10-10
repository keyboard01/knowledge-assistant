package com.hykj.assistant.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多查询的 RRF 融合（P1 查询改写用）。纯函数，有单测。
 *
 * <p>查询改写会产出"原问题 + 若干变体"，每条各自跑向量与关键词两路，于是融合的输入从"两路"
 * 变成"多路"。融合方式与 {@link RrfFusion} 完全一致——只看名次、不看跨通道分数：
 * **每一条通道里排第 i 的片段各贡献 {@code 1/(k+rank)}**。在多个查询里都排前面的片段自然累加得更高，
 * 这正是多查询的共识价值。</p>
 *
 * <p>精确词半票只有**一次**：多个关键词通道都强命中同一片段，也只加一份 {@code 0.5/(k+1)}。
 * 否则变体越多、同一片段被抬得越高，等于用"改写数量"当分数——那不是相关性。</p>
 *
 * <p>单路输入时与 {@link RrfFusion#fuse} 逐位等价（单测钉住），所以"改写关闭"那条路径可以
 * 直接沿用 {@code RrfFusion.fuse}，两条路径不会给出不同结果。</p>
 */
public final class MultiQueryFusion {

    private MultiQueryFusion() {
    }

    /**
     * @param vectorLists  每条的向量通道候选（各按相似度降序）
     * @param keywordLists 每条的关键词通道候选（各按相似度降序），与 vectorLists 按查询一一对应
     * @param k            RRF 常数
     * @param limit        最多返回几条；<=0 表示不截断
     */
    public static List<FusedChunk> fuse(List<List<ChunkRow>> vectorLists, List<List<ChunkRow>> keywordLists,
                                        int k, int limit) {
        Map<Long, Entry> merged = new LinkedHashMap<>();
        for (List<ChunkRow> hits : vectorLists) {
            rankInto(merged, hits, true, k);
        }
        for (List<ChunkRow> hits : keywordLists) {
            rankInto(merged, hits, false, k);
        }

        List<FusedChunk> fused = new ArrayList<>(merged.size());
        for (Entry entry : merged.values()) {
            double score = entry.fusedScore + (entry.strongKeyword ? RrfFusion.STRONG_KEYWORD_BONUS / (k + 1) : 0);
            fused.add(new FusedChunk(entry.row, score, entry.vectorScore, entry.keywordScore,
                    entry.vectorRank, entry.keywordRank));
        }
        Comparator<FusedChunk> order = Comparator
                .comparingDouble(FusedChunk::fusedScore).reversed()
                .thenComparing(Comparator.comparingDouble(FusedChunk::vectorScore).reversed())
                .thenComparingLong(item -> item.chunk().chunkId());
        fused.sort(order);
        return limit > 0 && fused.size() > limit ? List.copyOf(fused.subList(0, limit)) : List.copyOf(fused);
    }

    /**
     * 把一路候选并入合并表：融合分逐条累加（每条通道都算一份），
     * 但对外保留的名次取各路的**最优**、分数取最大值，只用于可解释与排障。
     */
    private static void rankInto(Map<Long, Entry> merged, List<ChunkRow> hits, boolean vector, int k) {
        for (int i = 0; i < hits.size(); i++) {
            ChunkRow row = hits.get(i);
            Entry entry = merged.computeIfAbsent(row.chunkId(), id -> new Entry(row));
            int rank = i + 1;
            entry.fusedScore += 1.0 / (k + rank);
            if (vector) {
                if (entry.vectorRank == 0 || rank < entry.vectorRank) {
                    entry.vectorRank = rank;
                }
                if (row.score() > entry.vectorScore) {
                    entry.vectorScore = row.score();
                }
            } else {
                if (entry.keywordRank == 0 || rank < entry.keywordRank) {
                    entry.keywordRank = rank;
                }
                if (row.score() > entry.keywordScore) {
                    entry.keywordScore = row.score();
                }
                if (row.score() >= RrfFusion.STRONG_KEYWORD_SIMILARITY) {
                    entry.strongKeyword = true;
                }
            }
        }
    }

    private static final class Entry {
        private final ChunkRow row;
        private double fusedScore;
        private int vectorRank;
        private int keywordRank;
        private double vectorScore;
        private double keywordScore;
        private boolean strongKeyword;

        private Entry(ChunkRow row) {
            this.row = row;
        }
    }
}
