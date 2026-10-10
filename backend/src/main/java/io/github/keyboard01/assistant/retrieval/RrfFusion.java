package io.github.keyboard01.assistant.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两路召回的融合（Reciprocal Rank Fusion）+ 精确词加成。纯函数，有单测。
 *
 * <p>为什么用 RRF 而不是把两路分数加权求和：两路的分数根本不可比——余弦相似度是 0~1 的几何量，
 * 关键词相似度是字符 trigram 的覆盖率，两者相加等于把"米"和"公斤"相加。
 * RRF 只看名次，天然免校准，这也是它成为混合检索默认做法的原因。</p>
 *
 * <p>精确词加成补 RRF 的一处短板：查询里的型号、编号、"每 2000 h"这类**精确词**命中时，
 * 这条片段几乎一定相关，但它可能两路名次都不靠前。所以给"关键词强命中"半票（0.5/(k+1)）——
 * 够把强命中抬上来，又不至于让关键词压过向量。</p>
 */
public final class RrfFusion {

    /** RRF 常数：60 出自原始论文，把名次之间的差距压得比较平缓。 */
    public static final int DEFAULT_K = 60;
    /** 关键词相似度到多少算"强命中"（trigram 相似度当尺子；实测跑题问题通常 < 0.2，精确词命中 > 0.35）。 */
    public static final double STRONG_KEYWORD_SIMILARITY = 0.35;
    /**
     * 关键词通道的**作答**下限：只有精确词命中到这条线以上，才允许"不作答阈值也能答"。
     *
     * <p>为什么比 {@link #STRONG_KEYWORD_SIMILARITY} 高：那个数管的是"要不要给这条片段加半票"，
     * 加错了只是名次上浮一点；这个数管的是"要不要放行一个答案"，放宽的代价是错误答案。
     * T17 实测把它钉住——应拒答的 Q22 拿到 0.353（来自查询里的"2026"，一个年份 trigram 就够得着 0.35），
     * 而真正靠关键词救场的 Q11 是 0.522。0.42 落在两者之间。</p>
     */
    public static final double ANSWER_KEYWORD_SIMILARITY = 0.42;
    /** 强命中的加成分量：半票。 */
    public static final double STRONG_KEYWORD_BONUS = 0.5;

    private RrfFusion() {
    }

    /**
     * @param vectorHits  向量通道的候选，按相似度从高到低
     * @param keywordHits 关键词通道的候选，按相似度从高到低
     * @param k           RRF 常数
     * @param limit       最多返回几条；<=0 表示不截断
     */
    public static List<FusedChunk> fuse(List<ChunkRow> vectorHits, List<ChunkRow> keywordHits, int k, int limit) {
        Map<Long, Entry> merged = new LinkedHashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            ChunkRow row = vectorHits.get(i);
            Entry entry = merged.computeIfAbsent(row.chunkId(), id -> new Entry(row));
            entry.vectorRank = i + 1;
            entry.vectorScore = row.score();
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            ChunkRow row = keywordHits.get(i);
            Entry entry = merged.computeIfAbsent(row.chunkId(), id -> new Entry(row));
            entry.keywordRank = i + 1;
            entry.keywordScore = row.score();
        }

        List<FusedChunk> fused = new ArrayList<>(merged.size());
        for (Entry entry : merged.values()) {
            double score = 0;
            if (entry.vectorRank > 0) {
                score += 1.0 / (k + entry.vectorRank);
            }
            if (entry.keywordRank > 0) {
                score += 1.0 / (k + entry.keywordRank);
                if (entry.keywordScore >= STRONG_KEYWORD_SIMILARITY) {
                    score += STRONG_KEYWORD_BONUS / (k + 1);
                }
            }
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

    private static final class Entry {
        private final ChunkRow row;
        private int vectorRank;
        private int keywordRank;
        private double vectorScore;
        private double keywordScore;

        private Entry(ChunkRow row) {
            this.row = row;
        }
    }
}