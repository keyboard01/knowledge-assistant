package io.github.keyboard01.assistant.retrieval;

/**
 * 一条候选证据。
 *
 * <p>{@code documentId} 是反查本地 {@code document} 行的钥匙（片段天然带着它，不必再靠文档名反查）。
 * 权限过滤与引用定位都靠它。</p>
 *
 * <p>片段还带回来"出自哪一页、哪一节"以及两路检索的名次：页码用来做引用定位（没有就留空，
 * 不编造），名次用来解释"为什么是它"——出问题时能看出是向量没召回还是关键词没召回。</p>
 *
 * <p>{@code score} 始终是**向量余弦相似度**，不是融合分：作答阈值比的是它，
 * 换成分数口径不同的融合分，阈值就失去意义了。</p>
 *
 * <p>{@code rerankScore} 是 P0 重排层引入后的额外信号：仅用于**排序**，不参与作答阈值判定。
 * 为空表示本次没走重排（未配置、开关关闭或重排失败 fail-open），此时排序回落到 {@code score}。</p>
 */
public record RetrievedPassage(
        String chunkId,
        Integer chunkIndex,
        long documentId,
        String text,
        double score,
        double distance,
        String published,
        int wordCount,
        int tokenCount,
        Integer pageFrom,
        Integer pageTo,
        String sectionPath,
        String kind,
        double keywordScore,
        int vectorRank,
        int keywordRank,
        double fusedScore,
        Double rerankScore) {

    public boolean hasPage() {
        return pageFrom != null;
    }

    public boolean hasRerankScore() {
        return rerankScore != null;
    }
}
