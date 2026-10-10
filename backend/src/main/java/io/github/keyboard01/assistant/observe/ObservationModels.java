package io.github.keyboard01.assistant.observe;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.github.keyboard01.assistant.generation.GeneratedAnswer;
import io.github.keyboard01.assistant.retrieval.RetrievalResult;

/**
 * 观测层的对外形状（AC-P1-011）。
 *
 * <p>刻意分成"列表行 / 明细 / 汇总"三种，而不是一个大对象：三者的读者不同——
 * 列表回答"最近问了什么、答得怎么样"，明细回答"这一次到底命中了什么"，
 * 汇总回答"这类问题一共多少次、花了多少钱"。合成一个，前端每次都得丢掉大半字段。</p>
 */
public final class ObservationModels {

    private ObservationModels() {
    }

    /**
     * 单价快照：记下"这条账是按什么价算出来的"。
     *
     * <p>不配置单价时两列为 null，{@code note} 说明原因——**不用 0 表示"不知道"**，
     * 0 会被读成"免费"，那是两个完全相反的结论。</p>
     */
    public record PriceSnapshot(BigDecimal promptPer1k, BigDecimal completionPer1k, String note) {

        public static final PriceSnapshot UNPRICED =
                new PriceSnapshot(null, null, "未配置单价：只记 token，不记费用");

        public boolean priced() {
            return promptPer1k != null && completionPer1k != null;
        }
    }

    /** 写一条账本的入参。检索账本与生成账本各自已经成型，这里只做搬运，不重新计算。 */
    public record TraceInput(String userName, String domainCode, String question,
                             RetrievalResult retrieval, GeneratedAnswer answer, PriceSnapshot price) {
    }

    /** 列表行：够看出"怎么回事"，不含逐条证据。 */
    public record TraceRow(long id, OffsetDateTime createdAt, String userName, String domainCode, String question,
                           boolean refused, String refusalRule, boolean insufficient, int evidenceCount,
                           int citedCount, Double bestScore, boolean modelCalled, int promptTokens,
                           int completionTokens, long totalElapsedMs, BigDecimal costTotal, boolean priced) {
    }

    /**
     * 一条命中证据。{@code cited} 是"模型有没有引用它"，不是"它有没有权限"——权限在检索阶段就已经判完了。
     *
     * <p>{@code rank} 是**融合之后**的最终名次；{@code vectorRank}/{@code keywordRank} 是**融合之前**
     * 两路各自的名次，0 表示那一路没召回它（AC-P2-007：只记最终名次，就没法回答"是哪一路失效了"）。
     * {@code score} 是向量余弦，{@code keywordScore} 是关键词相似度——两个量纲不混记。</p>
     */
    public record EvidenceRow(int rank, Long documentId, String docNo, String title, String version,
                              String chunkId, Integer chunkIndex, Double score, boolean cited, Integer pageFrom,
                              Integer pageTo, int vectorRank, int keywordRank, double keywordScore) {

        /** P2 T16 之前的形状：账本里还没有两路名次，补 0（= 未召回）。 */
        public EvidenceRow(int rank, Long documentId, String docNo, String title, String version,
                           String chunkId, Double score, boolean cited, Integer pageFrom, Integer pageTo) {
            this(rank, documentId, docNo, title, version, chunkId, null, score, cited, pageFrom, pageTo, 0, 0, 0);
        }
    }

    /** 明细：一次问答的全部账本字段 + 命中证据。 */
    public record TraceDetail(TraceRow row, String accessFilter, int requestedTopN, double minScore,
                              double answerMinScore, int kernelTopN, int kernelHits, int droppedUnauthorized,
                              int droppedUnknown, int droppedNotIndexed, int droppedDuplicate, int documentCount,
                              boolean versionConflict, long retrievalElapsedMs, long generationElapsedMs,
                              String model, List<Integer> outOfRangeCitations, boolean truncated,
                              BigDecimal promptPricePer1k, BigDecimal completionPricePer1k,
                              List<EvidenceRow> evidences) {
    }

    /**
     * 汇总。
     *
     * <p>{@code costTotal} 只累加**配了单价**的那些账，同时给出 {@code unpricedCount}：
     * 只报一个总额、不提有多少条没算钱，那个数字就是错的，而且错得不明显。</p>
     */
    public record Summary(long total, long refused, long insufficient, double refuseRate, long modelCalls,
                          long promptTokens, long completionTokens, BigDecimal costTotal, long pricedCount,
                          long unpricedCount, long avgElapsedMs, long maxElapsedMs, Double avgBestScore) {
    }
}
