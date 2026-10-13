package io.github.keyboard01.assistant.security;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 角色字典（app_role）。角色是可增的字典行，前端下拉据此生成，不写死。 */
@Repository
public class RoleRepository {

    public record RoleView(String code, String name, String description) {
    }

    private static final RowMapper<RoleView> MAPPER = (rs, rowNum) -> new RoleView(
            rs.getString("code"), rs.getString("name"), rs.getString("description"));

    private final JdbcTemplate jdbc;

    public RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoleView> findAll() {
        return jdbc.query("select code, name, description from app_role order by code", MAPPER);
    }

    public boolean exists(String code) {
        Integer count = jdbc.queryForObject("select count(*) from app_role where code = ?", Integer.class, code);
        return count != null && count > 0;
    }
}
