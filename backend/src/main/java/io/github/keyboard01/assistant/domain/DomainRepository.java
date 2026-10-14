package io.github.keyboard01.assistant.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DomainRepository {

    /** 迁移前就存在的域没有创建者，created_by 可为 null；tenant_code 由 V2 回填后为 NOT NULL。 */
    private static final RowMapper<KnowledgeDomain> MAPPER = (rs, rowNum) -> new KnowledgeDomain(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getBoolean("enabled"),
            rs.getString("tenant_code"),
            rs.getString("created_by"));

    private final JdbcTemplate jdbc;

    public DomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeDomain> findAll() {
        return jdbc.query("""
                select code, name, description, enabled, tenant_code, created_by
                  from knowledge_domain
                 order by sort_order, code
                """, MAPPER);
    }

    public Optional<KnowledgeDomain> findByCode(String code) {
        return jdbc.query("""
                select code, name, description, enabled, tenant_code, created_by
                  from knowledge_domain
                 where code = ?
                """, MAPPER, code).stream().findFirst();
    }

    public void insert(String code, String name, String description, int sortOrder,
                       String tenantCode, String createdBy) {
        jdbc.update("""
                insert into knowledge_domain (code, name, description, enabled, sort_order, tenant_code, created_by)
                values (?, ?, ?, true, ?, ?, ?)
                """, code, name, description, sortOrder, tenantCode, createdBy);
    }

    /** 只改可编辑字段：code 是主键，不在这里改。 */
    public void update(String code, String name, String description, boolean enabled) {
        jdbc.update("update knowledge_domain set name = ?, description = ?, enabled = ? where code = ?",
                name, description, enabled, code);
    }

    public void delete(String code) {
        jdbc.update("delete from knowledge_domain where code = ?", code);
    }

    public int maxSortOrder() {
        Integer max = jdbc.queryForObject("select coalesce(max(sort_order), 0) from knowledge_domain", Integer.class);
        return max == null ? 0 : max;
    }

    /**
     * 租户是否已存在。建域时先查一次：选错租户要给可读的 400，而不是等外键抛出难懂的 500。
     * （租户本身的 CRUD 属后续切片，到那时这张查询会挪到 TenantRepository。）
     */
    public boolean tenantExists(String tenantCode) {
        Integer count = jdbc.queryForObject("select count(*) from tenant where code = ?", Integer.class, tenantCode);
        return count != null && count > 0;
    }
}
