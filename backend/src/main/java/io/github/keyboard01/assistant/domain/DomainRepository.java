package io.github.keyboard01.assistant.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DomainRepository {

    private static final RowMapper<KnowledgeDomain> MAPPER = (rs, rowNum) -> new KnowledgeDomain(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public DomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeDomain> findAll() {
        return jdbc.query("""
                select code, name, description, enabled
                  from knowledge_domain
                 order by sort_order, code
                """, MAPPER);
    }

    public Optional<KnowledgeDomain> findByCode(String code) {
        return jdbc.query("""
                select code, name, description, enabled
                  from knowledge_domain
                 where code = ?
                """, MAPPER, code).stream().findFirst();
    }

    public void insert(String code, String name, String description, int sortOrder) {
        jdbc.update("""
                insert into knowledge_domain (code, name, description, enabled, sort_order)
                values (?, ?, ?, true, ?)
                """, code, name, description, sortOrder);
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
}
