package io.github.keyboard01.assistant.observe;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评测层的对外形状（P3）。
 *
 * <p>题集是磁盘上的 JSON（`tools/questions/*.json`，与既有验收脚本同一份文件），
 * 判定结果与指标落库，趋势靠查库而不是靠翻日志。</p>
 */
public final class EvalModels {

    private EvalModels() {
    }

    /**
     * 一道题。字段全部可缺省——题集是人手写的，缺 `mention` 就该只是"不校验必含事实"，
     * 而不是整份题集解析失败。
     */
    public record Case(String caseId, String domainCode, String asker, String question, String reasoning,
                       String expectedOutcome, boolean permission, List<String> allowedDocNos,
                       List<String> mention, List<String> forbiddenDocNos, List<String> noLeak) {
    }

    /**
     * 一道题的判定结果。{@code docRank} 是首个期望文档在证据里的位次（1 起，0 = 没命中）。
     *
     * <p>{@code citationOk} 只在 full 模式且真的作答时有值：引用非空、且没引用期望之外的文档。
     * 单独留一个字段而不是从失败原因里认字，是因为"引用正确率"必须能被单独统计——
     * 它回答的是"答案有没有出处"，与"答得对不对"是两件事。</p>
     */
    public record CaseResult(String caseId, String domainCode, String asker, String question, String reasoning,
                             String expectedOutcome, String actualOutcome, boolean ok, String failureReason,
                             boolean leaked, Boolean citationOk, Long traceId, boolean hit, int docRank,
                             Double bestScore, String expectedDocNos, String citedDocNos, long elapsedMs) {
    }

    /** 一次运行。RUNNING 是中间态：先把行插进去、逐题回填，中途中断也能看到跑到哪了。 */
    public record RunRow(long id, OffsetDateTime createdAt, String createdBy, String questionSet, String mode,
                         String status, int total, int passed, int failed, Double hitAtK, Double mrr,
                         Double citationAccuracy, Double refusalAccuracy, int leakCount, Double avgBestScore,
                         long avgElapsedMs, long promptTokens, long completionTokens, BigDecimal costTotal,
                         String note) {
    }

    public record RunDetail(RunRow run, List<CaseResult> cases) {
    }

    /** 汇总指标。{@code hitAtK} 与 {@code mrr} 的分母是"有期望文档的题数"。 */
    public record Metrics(double hitAtK, double mrr, Double citationAccuracy, Double refusalAccuracy,
                          int leakCount, Double avgBestScore, long avgElapsedMs, int passed, int failed,
                          long promptTokens, long completionTokens, BigDecimal costTotal) {
    }
}
