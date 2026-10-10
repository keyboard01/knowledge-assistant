package io.github.keyboard01.assistant.retrieval;

/**
 * 一次检索的账本：AC-P1-011 要记录"查询词、过滤条件、命中片段、耗时"，P2 又多了两路召回的账
 * （AC-P2-007）——出问题时必须能看出是向量没召回、关键词没召回，还是融合把顺序排错了。
 *
 * <p>{@code accessFilter} 存的是**当时真正下推的那份条件**，而不是事后按身份重新推一遍：
 * 重新推的能一样，但那是巧合，不是保证。</p>
 *
 * <p>{@code kernelHits} 是检索实现交回的候选总数；{@code vectorHits}/{@code keywordHits} 是其中
 * 分别被两路命中的条数（同一条可能两路都命中）。{@code evidenceCount} 是截断后真正交给生成的条数，
 * {@code documentCount} 是过滤通过、产生证据的文档总数。</p>
 *
 * <p>{@code bestScore} 与 {@code bestKeywordScore} 分别是两路的最强分，两者都低于各自的作答阈值时
 * 才判"证据不足"——只报一个数，就没法解释"为什么这次没答"。</p>
 */
public record RetrievalDiagnostics(String accessFilter, int requestedTopN, double minScore, double answerMinScore,
                                   double keywordMinScore,
                                   int kernelTopN,
                                   int kernelHits,
                                   int vectorHits,
                                   int keywordHits,
                                   double bestKeywordScore,
                                   int droppedUnauthorized, int droppedUnknown, int droppedNotIndexed,
                                   int droppedDuplicate, int evidenceCount, int documentCount, double bestScore,
                                   boolean versionConflict, long elapsedMs,
                                   boolean rerankApplied, double bestRerankScore) {
}