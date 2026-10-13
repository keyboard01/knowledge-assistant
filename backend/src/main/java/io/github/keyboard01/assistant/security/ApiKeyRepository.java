package io.github.keyboard01.assistant.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 对外 API Key 的存取。与 {@link SessionRepository} 同规格：库里只存 SHA-256，明文只在创建时出现一次。
 */
@Repository
public class ApiKeyRepository {

    public record Row(long id, String name, String keyPrefix, long userId, boolean enabled, String createdBy,
                      Instant createdAt, Instant lastUsedAt) {
    }

    private static final String COLUMNS =
            "id, name, key_prefix, user_id, enabled, created_by, created_at, last_used_at";

    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> new Row(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("key_prefix"),
            rs.getLong("user_id"),
            rs.getBoolean("enabled"),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("last_used_at")));

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name, String keyPrefix, String keyHash, long userId, String createdBy) {
        Long id = jdbc.queryForObject("""
                insert into api_client (name, key_prefix, key_hash, user_id, created_by)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, name, keyPrefix, keyHash, userId, createdBy);
        return id == null ? 0L : id;
    }

    /** 鉴权只认启用中的 key。 */
    public Optional<Row> findActiveByHash(String keyHash) {
        return jdbc.query("select " + COLUMNS + " from api_client where key_hash = ? and enabled",
                MAPPER, keyHash).stream().findFirst();
    }

    public Optional<Row> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from api_client where id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Row> findAll() {
        return jdbc.query("select " + COLUMNS + " from api_client order by id", MAPPER);
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.update("update api_client set enabled = ? where id = ?", enabled, id);
    }

    public void revoke(long id) {
        jdbc.update("delete from api_client where id = ?", id);
    }

    /** 调用方已在内存里做过节流判断，这里只写。 */
    public void touchUsed(long id) {
        jdbc.update("update api_client set last_used_at = now() where id = ?", id);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
