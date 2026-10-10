package com.hykj.assistant.retrieval;

/**
 * 一条候选证据（业务语言，不含任何内核私有格式）。
 *
 * <p>内核会往正文最前面插入一段 {@code <document_metadata>} 块，那是内核的实现细节；适配器已把它剥掉，
 * 所以 {@link #text()} 就是可以直接展示、直接喂给模型的原文。</p>
 *
 * <p>{@code kernelDocName}（形如 {@code QM-ZD-2026-003_V3.2.pdf}）是反查本地 {@code document} 行的钥匙：
 * 命中的片段只带得回文档名，带不回我们的主键。权限过滤与引用定位都靠它。</p>
 *
 * <p>P2 起片段还带回来"出自哪一页、哪一节"以及两路检索的名次：页码用来做引用定位（没有就留空，
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
        String kernelDocName,
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

    /** P1 的调用方式：只有内核那一路的字段。 */
    public RetrievedPassage(String chunkId, String kernelDocName, String text, double score, double distance,
                            String published, int wordCount, int tokenCount) {
        this(chunkId, null, kernelDocName, text, score, distance, published, wordCount, tokenCount,
                null, null, null, null, 0, 0, 0, 0, null);
    }

    public boolean hasPage() {
        return pageFrom != null;
    }

    public boolean hasRerankScore() {
        return rerankScore != null;
    }
}
