package io.github.keyboard01.assistant.retrieval;

/**
 * 融合后的候选。保留"两路各自第几名"是为了可解释：
 * 出问题时能一眼看出是哪一路失效了（向量没召回？关键词没召回？还是融合把顺序排错了）。
 */
public record FusedChunk(ChunkRow chunk, double fusedScore, double vectorScore, double keywordScore,
                         int vectorRank, int keywordRank) {

    public boolean hitByVector() {
        return vectorRank > 0;
    }

    public boolean hitByKeyword() {
        return keywordRank > 0;
    }
}