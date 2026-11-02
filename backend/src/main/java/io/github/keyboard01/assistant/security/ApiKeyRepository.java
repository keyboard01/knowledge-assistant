package io.github.keyboard01.assistant.security;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 对外 API Key 的存取。与 {@link SessionRepository} 同规格：库里只存 SHA-256，明文只在创建时出现一次。
 *
 * <p>S5 起带自身范围（{@code tenant_codes}/{@code domain_codes}，空数组=不限），见 {@link KeyScope}。</p>
 */
@Repository
public class ApiKeyRepository {

    public record Row(long id, String name, String keyPrefix, long userId, boolean enabled, String createdBy,
                      Instant createdAt, Instant lastUsedAt, Set<String> tenantCodes, Set<String> domainCodes) {

        /** Row 上的两个绑定落到纯函数模型上；绑定账号的读取在 {@link ApiKeyService}。 */
        public KeyScope toScope(String endUser) {
            return new KeyScope(tenantCodes, domainCodes, endUser);
        }
    }

    private static final String COLUMNS =
            "id, name, key_prefix, user_id, enabled, created_by, created_at, last_used_at, tenant_codes, domain_codes";

    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> new Row(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("key_prefix"),
            rs.getLong("user_id"),
            rs.getBoolean("enabled"),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("last_used_at")),
            readCodes(rs, "tenant_codes"),
            readCodes(rs, "domain_codes"));

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name, String keyPrefix, String keyHash, long userId, String createdBy,
                       Set<String> tenantCodes, Set<String> domainCodes) {
        List<Long> ids = jdbc.query("""
                insert into api_client (name, key_prefix, key_hash, user_id, created_by, tenant_codes, domain_codes)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, ps -> {
            ps.setString(1, name);
            ps.setString(2, keyPrefix);
            ps.setString(3, keyHash);
            ps.setLong(4, userId);
            ps.setString(5, createdBy);
            writeCodes(ps, 6, tenantCodes);
            writeCodes(ps, 7, domainCodes);
        }, (rs, rowNum) -> rs.getLong(1));
        return ids.isEmpty() ? 0L : ids.get(0);
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

    /** S5：改绑租户/域范围（空集合=不限）。绑定账号不在这里改——那是"权限即账号"的事。 */
    public void updateScope(long id, Set<String> tenantCodes, Set<String> domainCodes) {
        jdbc.update("update api_client set tenant_codes = ?, domain_codes = ? where id = ?",
                (ps) -> {
                    writeCodes(ps, 1, tenantCodes);
                    writeCodes(ps, 2, domainCodes);
                    ps.setLong(3, id);
                });
    }

    public void revoke(long id) {
        jdbc.update("delete from api_client where id = ?", id);
    }

    /** 调用方已在内存里做过节流判断，这里只写。 */
    public void touchUsed(long id) {
        jdbc.update("update api_client set last_used_at = now() where id = ?", id);
    }

    /** text[] 的写入：走 {@code createArrayOf}，不拼字面量，驱动负责转义。 */
    private static void writeCodes(PreparedStatement ps, int index, Set<String> codes) throws SQLException {
        ps.setArray(index, ps.getConnection().createArrayOf("text", codes.toArray(String[]::new)));
    }

    /** text[] 的读取：null/坏值一律落空集合，语义是"不限"——绑定只该通过管理端写入，这里不做防御性收窄。 */
    private static Set<String> readCodes(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return Set.of();
        }
        Set<String> codes = new TreeSet<>();
        for (String code : (String[]) array.getArray()) {
            if (code != null && !code.isBlank()) {
                codes.add(code.trim());
            }
        }
        return Set.copyOf(codes);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
