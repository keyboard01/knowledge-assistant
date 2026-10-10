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
            rs.getString("workspace_slug"),
            rs.getString("description"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public DomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeDomain> findAll() {
        return jdbc.query("""
                select code, name, workspace_slug, description, enabled
                  from knowledge_domain
                 order by sort_order, code
                """, MAPPER);
    }

    public Optional<KnowledgeDomain> findByCode(String code) {
        return jdbc.query("""
                select code, name, workspace_slug, description, enabled
                  from knowledge_domain
                 where code = ?
                """, MAPPER, code).stream().findFirst();
    }

    /** 内核实际给出的 slug 与登记值不一致时，以内核为准回写。 */
    public void updateWorkspaceSlug(String code, String workspaceSlug) {
        jdbc.update("update knowledge_domain set workspace_slug = ? where code = ?", workspaceSlug, code);
    }

    public void insert(String code, String name, String workspaceSlug, String description, int sortOrder) {
        jdbc.update("""
                insert into knowledge_domain (code, name, workspace_slug, description, enabled, sort_order)
                values (?, ?, ?, ?, true, ?)
                """, code, name, workspaceSlug, description, sortOrder);
    }

    /** 只改可编辑字段：code 是主键、workspace_slug 由内核决定，都不在这里改。 */
    public void update(String code, String name, String description, boolean enabled) {
        jdbc.update("update knowledge_domain set name = ?, description = ?, enabled = ? where code = ?",
                name, description, enabled, code);
    }

    public void delete(String code) {
        jdbc.update("delete from knowledge_domain where code = ?", code);
    }

    /**
     * slug 唯一性守卫：一个工作区不能对应两个知识域（除自己之外）。
     *
     * <p>刻意拆成两条 SQL，而不是 {@code (? is null or code <> ?)}：后者里那个"可能是 null 的
     * 未定型占位符"会让 Postgres 报 {@code could not determine data type of parameter $2}——
     * 单测碰不到（要连库），只在运行时炸。</p>
     */
    public boolean existsWorkspaceSlug(String workspaceSlug, String exceptCode) {
        Integer count;
        if (exceptCode == null || exceptCode.isBlank()) {
            count = jdbc.queryForObject(
                    "select count(*) from knowledge_domain where workspace_slug = ?",
                    Integer.class, workspaceSlug);
        } else {
            count = jdbc.queryForObject(
                    "select count(*) from knowledge_domain where workspace_slug = ? and code <> ?",
                    Integer.class, workspaceSlug, exceptCode);
        }
        return count != null && count > 0;
    }

    public int maxSortOrder() {
        Integer max = jdbc.queryForObject("select coalesce(max(sort_order), 0) from knowledge_domain", Integer.class);
        return max == null ? 0 : max;
    }
}
