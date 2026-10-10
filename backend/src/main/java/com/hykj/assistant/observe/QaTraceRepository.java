package com.hykj.assistant.observe;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.hykj.assistant.observe.ObservationModels.EvidenceRow;
import com.hykj.assistant.observe.ObservationModels.Summary;
import com.hykj.assistant.observe.ObservationModels.TraceDetail;
import com.hykj.assistant.observe.ObservationModels.TraceInput;
import com.hykj.assistant.observe.ObservationModels.TraceRow;
import com.hykj.assistant.retrieval.Evidence;

/**
 * 运行记录的读写（T10）。
 *
 * <p>可空的数值与文本参数一律在 SQL 里显式 cast：JdbcTemplate 传 null 时用的是无类型 setObject，
 * PostgreSQL 无法推断参数类型会直接报错，而"可选字段为空"在这张表里是常态（未配单价、没有越界引用）。</p>
 */
@Repository
public class QaTraceRepository {

    private static final String ROW_COLUMNS = """
            id, created_at, user_name, domain_code, question, refused, refusal_rule, insufficient,
            evidence_count, cited_count, best_score, model_called, prompt_tokens, completion_tokens,
            total_elapsed_ms, cost_total
            """;

    private static final RowMapper<TraceRow> ROW_MAPPER = (rs, rowNum) -> new TraceRow(
            rs.getLong("id"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getString("user_name"),
            rs.getString("domain_code"),
            rs.getString("question"),
            rs.getBoolean("refused"),
            rs.getString("refusal_rule"),
            rs.getBoolean("insufficient"),
            rs.getInt("evidence_count"),
            rs.getInt("cited_count"),
            // numeric 一律走 getBigDecimal 再转：getObject(Class) 的数值转换随驱动而异，
            // 账本读不出来比账本写不进去更难查（写不进去至少报错）。
            toDouble(rs.getBigDecimal("best_score")),
            rs.getBoolean("model_called"),
            rs.getInt("prompt_tokens"),
            rs.getInt("completion_tokens"),
            rs.getLong("total_elapsed_ms"),
            rs.getBigDecimal("cost_total"),
            rs.getBigDecimal("cost_total") != null);

    private final JdbcTemplate jdbc;

    public QaTraceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写一条账本，返回主键。 */
    public long insert(TraceInput input, String outOfRange, long generationElapsedMs, BigDecimal costTotal) {
        var retrieval = input.retrieval().diagnostics();
        var answer = input.answer();
        var generation = answer.diagnostics();
        return jdbc.queryForObject("""
                insert into qa_trace (
                    user_name, domain_code, question, access_filter,
                    requested_top_n, min_score, answer_min_score, kernel_top_n, kernel_hits,
                    dropped_unauthorized, dropped_unknown, dropped_not_indexed, dropped_duplicate,
                    evidence_count, document_count, best_score, version_conflict, insufficient, retrieval_elapsed_ms,
                    model, model_called, refused, refusal_rule, cited_count, out_of_range, truncated,
                    prompt_tokens, completion_tokens, generation_elapsed_ms, total_elapsed_ms,
                    prompt_price_per_1k, completion_price_per_1k, cost_total
                ) values (
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, cast(? as numeric), ?, ?, ?,
                    cast(? as varchar), ?, ?, cast(? as varchar), ?, cast(? as text), ?,
                    ?, ?, ?, ?,
                    cast(? as numeric), cast(? as numeric), cast(? as numeric)
                ) returning id
                """, Long.class,
                input.userName(), input.domainCode(), input.question(), retrieval.accessFilter(),
                retrieval.requestedTopN(), retrieval.minScore(), retrieval.answerMinScore(),
                retrieval.kernelTopN(), retrieval.kernelHits(),
                retrieval.droppedUnauthorized(), retrieval.droppedUnknown(), retrieval.droppedNotIndexed(),
                retrieval.droppedDuplicate(),
                retrieval.evidenceCount(), retrieval.documentCount(), retrieval.bestScore(),
                retrieval.versionConflict(), input.retrieval().insufficient(), retrieval.elapsedMs(),
                generation.model(), generation.modelCalled(), answer.refused(), generation.refusalRule(),
                generation.citedCount(), outOfRange, generation.truncated(),
                generation.promptTokens(), generation.completionTokens(), generationElapsedMs,
                generation.elapsedMs(),
                input.price().promptPer1k(), input.price().completionPer1k(), costTotal);
    }

    /**
     * 命中片段明细：一条片段一行（同一份证据的多个片段共用 rank）。
     * 按片段而不是按文档存，是因为"这个片段被检索到过几次"比"这份文档被检索到过几次"更有用——
     * 决定要不要留它的是片段级的相关性。{@code cited} 表示模型引用了它。
     */
    public void insertFragments(long traceId, List<Evidence> evidences, java.util.Set<String> citedChunkIds) {
        if (evidences == null || evidences.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (Evidence evidence : evidences) {
            for (Evidence.Fragment fragment : evidence.fragments()) {
                batch.add(new Object[]{
                        traceId, evidence.rank(), evidence.documentId(), evidence.docNo(), evidence.title(),
                        evidence.version(), fragment.chunkId(), fragment.chunkIndex(), fragment.score(),
                        citedChunkIds.contains(fragment.chunkId()), fragment.pageFrom(), fragment.pageTo(),
                        fragment.vectorRank(), fragment.keywordRank(), fragment.keywordScore()
                });
            }
        }
        jdbc.batchUpdate("""
                insert into qa_trace_evidence
                    (trace_id, rank, document_id, doc_no, title, version, chunk_id, chunk_index, score, cited,
                     page_from, page_to, vector_rank, keyword_rank, keyword_score)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as int), cast(? as numeric), ?, ?, ?, ?, ?, cast(? as numeric))
                """, batch);
    }

    public List<TraceRow> recent(int limit, String domainCode, Boolean refused) {
        StringBuilder sql = new StringBuilder("select " + ROW_COLUMNS + " from qa_trace where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (domainCode != null && !domainCode.isBlank()) {
            sql.append(" and domain_code = ?");
            params.add(domainCode.trim());
        }
        if (refused != null) {
            sql.append(" and refused = ?");
            params.add(refused);
        }
        sql.append(" order by created_at desc, id desc limit ?");
        params.add(limit);
        return jdbc.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    public Optional<TraceDetail> find(long id) {
        List<TraceDetail> found = jdbc.query("""
                select %s,
                       access_filter, requested_top_n, min_score, answer_min_score, kernel_top_n, kernel_hits,
                       dropped_unauthorized, dropped_unknown, dropped_not_indexed, dropped_duplicate,
                       document_count, version_conflict, retrieval_elapsed_ms, generation_elapsed_ms,
                       model, out_of_range, truncated, prompt_price_per_1k, completion_price_per_1k
                  from qa_trace
                 where id = ?
                """.formatted(ROW_COLUMNS), (rs, rowNum) -> {
            TraceRow row = ROW_MAPPER.mapRow(rs, rowNum);
            return new TraceDetail(row,
                    rs.getString("access_filter"),
                    rs.getInt("requested_top_n"),
                    rs.getDouble("min_score"),
                    rs.getDouble("answer_min_score"),
                    rs.getInt("kernel_top_n"),
                    rs.getInt("kernel_hits"),
                    rs.getInt("dropped_unauthorized"),
                    rs.getInt("dropped_unknown"),
                    rs.getInt("dropped_not_indexed"),
                    rs.getInt("dropped_duplicate"),
                    rs.getInt("document_count"),
                    rs.getBoolean("version_conflict"),
                    rs.getLong("retrieval_elapsed_ms"),
                    rs.getLong("generation_elapsed_ms"),
                    rs.getString("model"),
                    parseOutOfRange(rs.getString("out_of_range")),
                    rs.getBoolean("truncated"),
                    rs.getBigDecimal("prompt_price_per_1k"),
                    rs.getBigDecimal("completion_price_per_1k"),
                    List.of());
        }, id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TraceDetail detail = found.get(0);
        return Optional.of(new TraceDetail(detail.row(), detail.accessFilter(), detail.requestedTopN(),
                detail.minScore(), detail.answerMinScore(), detail.kernelTopN(), detail.kernelHits(),
                detail.droppedUnauthorized(), detail.droppedUnknown(), detail.droppedNotIndexed(),
                detail.droppedDuplicate(), detail.documentCount(), detail.versionConflict(),
                detail.retrievalElapsedMs(), detail.generationElapsedMs(), detail.model(),
                detail.outOfRangeCitations(), detail.truncated(), detail.promptPricePer1k(),
                detail.completionPricePer1k(), fragmentsOf(id)));
    }

    private List<EvidenceRow> fragmentsOf(long traceId) {
        return jdbc.query("""
                select rank, document_id, doc_no, title, version, chunk_id, chunk_index, score, cited,
                       page_from, page_to, vector_rank, keyword_rank, keyword_score
                  from qa_trace_evidence
                 where trace_id = ?
                 order by rank, score desc
                """, (rs, rowNum) -> new EvidenceRow(
                rs.getInt("rank"),
                rs.getObject("document_id", Long.class),
                rs.getString("doc_no"),
                rs.getString("title"),
                rs.getString("version"),
                rs.getString("chunk_id"),
                rs.getObject("chunk_index", Integer.class),
                toDouble(rs.getBigDecimal("score")),
                rs.getBoolean("cited"),
                rs.getObject("page_from", Integer.class),
                rs.getObject("page_to", Integer.class),
                rs.getInt("vector_rank"),
                rs.getInt("keyword_rank"),
                rs.getBigDecimal("keyword_score").doubleValue()), traceId);
    }

    public Summary summary(String domainCode, Boolean refused) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (domainCode != null && !domainCode.isBlank()) {
            where.append(" and domain_code = ?");
            params.add(domainCode.trim());
        }
        if (refused != null) {
            where.append(" and refused = ?");
            params.add(refused);
        }
        return jdbc.queryForObject("""
                select count(*)                                                                  as total,
                       count(*) filter (where refused)                                           as refused,
                       count(*) filter (where insufficient)                                      as insufficient,
                       count(*) filter (where model_called)                                      as model_calls,
                       coalesce(sum(prompt_tokens), 0)                                           as prompt_tokens,
                       coalesce(sum(completion_tokens), 0)                                       as completion_tokens,
                       coalesce(sum(cost_total), 0)                                              as cost_total,
                       count(*) filter (where cost_total is not null)                            as priced_count,
                       count(*) filter (where cost_total is null)                                as unpriced_count,
                       coalesce(round(avg(total_elapsed_ms)), 0)                                 as avg_elapsed,
                       coalesce(max(total_elapsed_ms), 0)                                        as max_elapsed,
                       avg(best_score)                                                           as avg_best_score
                  from qa_trace
                """ + where, (rs, rowNum) -> new Summary(
                rs.getLong("total"),
                rs.getLong("refused"),
                rs.getLong("insufficient"),
                rs.getLong("total") == 0 ? 0d
                        : Math.round(rs.getLong("refused") * 10000d / rs.getLong("total")) / 100d,
                rs.getLong("model_calls"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getBigDecimal("cost_total"),
                rs.getLong("priced_count"),
                rs.getLong("unpriced_count"),
                rs.getLong("avg_elapsed"),
                rs.getLong("max_elapsed"),
                toDouble(rs.getBigDecimal("avg_best_score"))), params.toArray());
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** 越界引用按"1,3"存文本：它只在出问题时才有值，为它单开一列数组类型不值得。 */
    private static List<Integer> parseOutOfRange(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> parsed = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                parsed.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 账本里的脏数据不该让查询失败
            }
        }
        return parsed;
    }
}
