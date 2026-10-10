package io.github.keyboard01.assistant.ingest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 每次尝试都写一行，不覆盖历史——验收要求"失败记录 + 重试成功记录"能同时看到。
 */
@Repository
public class IngestTaskRepository {

    public record Row(long id, Long documentId, String sourcePath, String contentHash, String status,
                      int attempts, String lastError, String action, String detail,
                      OffsetDateTime createdAt, OffsetDateTime finishedAt) {
    }

    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> {
        Number documentId = (Number) rs.getObject("document_id");
        return new Row(
                rs.getLong("id"),
                documentId == null ? null : documentId.longValue(),
                rs.getString("source_path"),
                rs.getString("content_hash"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getString("action"),
                rs.getString("detail"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class));
    };

    private final JdbcTemplate jdbc;

    public IngestTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String sourcePath, String contentHash, int attempts) {
        Long id = jdbc.queryForObject("""
                insert into ingest_task (source_path, content_hash, status, attempts, started_at)
                values (?, ?, 'RUNNING', ?, now())
                returning id
                """, Long.class, sourcePath, contentHash, attempts);
        return id == null ? 0L : id;
    }

    public void finish(long id, Long documentId, String status, String action, String detail, String error) {
        jdbc.update("""
                update ingest_task set document_id = ?, status = ?, action = ?, detail = ?, last_error = ?,
                       finished_at = now()
                 where id = ?
                """, documentId, status, action, detail, error, id);
    }

    public Optional<Row> findById(long id) {
        return jdbc.query("select * from ingest_task where id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Row> recent(int limit) {
        return jdbc.query("select * from ingest_task order by id desc limit ?", MAPPER, limit);
    }

    /** 同一个文件的历史尝试次数，重试时累加，便于看出"试了几次"。 */
    public int latestAttempts(String sourcePath) {
        Integer value = jdbc.queryForObject(
                "select coalesce(max(attempts), 0) from ingest_task where source_path = ?", Integer.class, sourcePath);
        return value == null ? 0 : value;
    }

    /** 每个文件只看最后一次尝试，且那次是 FAILED 的才算"待重试"。 */
    public List<String> failedSourcePaths() {
        return jdbc.queryForList("""
                select source_path from (
                    select distinct on (source_path) source_path, status
                      from ingest_task
                     order by source_path, id desc
                ) latest
                 where status = 'FAILED'
                 order by source_path
                """, String.class);
    }

    public Optional<Row> latestForPath(String sourcePath) {
        return jdbc.query("select * from ingest_task where source_path = ? order by id desc limit 1",
                MAPPER, sourcePath).stream().findFirst();
    }
}
