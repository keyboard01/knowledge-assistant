package io.github.keyboard01.assistant.observe;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.github.keyboard01.assistant.observe.EvalModels.CaseResult;
import io.github.keyboard01.assistant.observe.EvalModels.Metrics;
import io.github.keyboard01.assistant.observe.EvalModels.RunRow;

/**
 * 评测结果的读写（P3）。
 *
 * <p>与 {@link QaTraceRepository} 同一条约定：可空参数在 SQL 里显式 cast——JdbcTemplate 传 null
 * 时用的是无类型 setObject，PostgreSQL 推断不出类型会直接报错，而"这项指标这轮没有样本"是常态。</p>
 */
@Repository
public class EvalRepository {

    private static final String RUN_COLUMNS = """
            id, created_at, created_by, question_set, mode, status, total, passed, failed, hit_at_k, mrr,
            citation_accuracy, refusal_accuracy, leak_count, avg_best_score, avg_elapsed_ms,
            prompt_tokens, completion_tokens, cost_total, note
            """;

    private static final RowMapper<RunRow> RUN_MAPPER = (rs, rowNum) -> new RunRow(
            rs.getLong("id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("created_by"),
            rs.getString("question_set"),
            rs.getString("mode"),
            rs.getString("status"),
            rs.getInt("total"),
            rs.getInt("passed"),
            rs.getInt("failed"),
            toDouble(rs.getBigDecimal("hit_at_k")),
            toDouble(rs.getBigDecimal("mrr")),
            toDouble(rs.getBigDecimal("citation_accuracy")),
            toDouble(rs.getBigDecimal("refusal_accuracy")),
            rs.getInt("leak_count"),
            toDouble(rs.getBigDecimal("avg_best_score")),
            rs.getLong("avg_elapsed_ms"),
            rs.getLong("prompt_tokens"),
            rs.getLong("completion_tokens"),
            rs.getBigDecimal("cost_total"),
            rs.getString("note"));

    private final JdbcTemplate jdbc;

    public EvalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 开跑先落一行 RUNNING：跑一半被中断时，至少能看到"跑到哪、已完成的题怎么样"。 */
    public long insertRun(String createdBy, String questionSet, String mode) {
        return jdbc.queryForObject("""
                insert into eval_run (created_by, question_set, mode, status)
                values (?, ?, ?, 'RUNNING') returning id
                """, Long.class, createdBy, questionSet, mode);
    }

    public void insertCase(long runId, CaseResult result) {
        jdbc.update("""
                insert into eval_case (run_id, case_id, domain_code, asker, question, reasoning,
                                       expected_outcome, actual_outcome, ok, failure_reason, leaked, citation_ok,
                                       trace_id, hit, doc_rank, best_score, expected_doc_nos, cited_doc_nos,
                                       elapsed_ms)
                values (?, ?, ?, ?, ?, cast(? as varchar), ?, ?, ?, cast(? as text), ?, ?, ?, ?, ?,
                        cast(? as numeric), cast(? as text), cast(? as text), ?)
                """, runId, result.caseId(), result.domainCode(), result.asker(), result.question(),
                result.reasoning(), result.expectedOutcome(), result.actualOutcome(), result.ok(),
                result.failureReason(), result.leaked(), result.citationOk(), result.traceId(), result.hit(),
                result.docRank(), result.bestScore(), result.expectedDocNos(), result.citedDocNos(),
                result.elapsedMs());
    }

    public void finishRun(long runId, Metrics metrics) {
        jdbc.update("""
                update eval_run
                   set status = 'DONE', total = ?, passed = ?, failed = ?, hit_at_k = cast(? as numeric),
                       mrr = cast(? as numeric), citation_accuracy = cast(? as numeric),
                       refusal_accuracy = cast(? as numeric), leak_count = ?, avg_best_score = cast(? as numeric),
                       avg_elapsed_ms = ?, prompt_tokens = ?, completion_tokens = ?,
                       cost_total = cast(? as numeric), finished_at = now()
                 where id = ?
                """, metrics.passed() + metrics.failed(), metrics.passed(), metrics.failed(),
                metrics.hitAtK(), metrics.mrr(), metrics.citationAccuracy(), metrics.refusalAccuracy(),
                metrics.leakCount(), metrics.avgBestScore(), metrics.avgElapsedMs(), metrics.promptTokens(),
                metrics.completionTokens(), metrics.costTotal(), runId);
    }

    /** 跑到一半炸了也要留痕：一行 FAILED 比"什么都没有"更容易发现。 */
    public void failRun(long runId, String note) {
        jdbc.update("update eval_run set status = 'FAILED', note = cast(? as text), finished_at = now() where id = ?",
                note, runId);
    }

    public List<RunRow> recent(int limit, String questionSet) {
        StringBuilder sql = new StringBuilder("select " + RUN_COLUMNS + " from eval_run where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (questionSet != null && !questionSet.isBlank()) {
            sql.append(" and question_set = ?");
            params.add(questionSet.trim());
        }
        sql.append(" order by created_at desc, id desc limit ?");
        params.add(limit);
        return jdbc.query(sql.toString(), RUN_MAPPER, params.toArray());
    }

    public Optional<RunRow> findRun(long id) {
        return jdbc.query("select " + RUN_COLUMNS + " from eval_run where id = ?", RUN_MAPPER, id)
                .stream().findFirst();
    }

    public List<CaseResult> cases(long runId) {
        return jdbc.query("""
                select case_id, domain_code, asker, question, reasoning, expected_outcome, actual_outcome,
                       ok, failure_reason, leaked, citation_ok, trace_id, hit, doc_rank, best_score,
                       expected_doc_nos, cited_doc_nos, elapsed_ms
                  from eval_case
                 where run_id = ?
                 order by id
                """, (rs, rowNum) -> new CaseResult(
                rs.getString("case_id"), rs.getString("domain_code"), rs.getString("asker"),
                rs.getString("question"), rs.getString("reasoning"), rs.getString("expected_outcome"),
                rs.getString("actual_outcome"), rs.getBoolean("ok"), rs.getString("failure_reason"),
                rs.getBoolean("leaked"), (Boolean) rs.getObject("citation_ok"),
                (Long) rs.getObject("trace_id"), rs.getBoolean("hit"), rs.getInt("doc_rank"),
                toDouble(rs.getBigDecimal("best_score")), rs.getString("expected_doc_nos"),
                rs.getString("cited_doc_nos"), rs.getLong("elapsed_ms")), runId);
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
