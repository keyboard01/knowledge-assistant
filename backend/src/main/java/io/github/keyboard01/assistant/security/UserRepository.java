package io.github.keyboard01.assistant.security;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String COLUMNS = "id, username, display_name, role_code, dept_code, enabled";

    private static final RowMapper<AppUser> MAPPER = (rs, rowNum) -> new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("role_code"),
            rs.getString("dept_code"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("select " + COLUMNS + " from app_user where username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from app_user where id = ?", MAPPER, id).stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbc.query("select " + COLUMNS + " from app_user order by username", MAPPER);
    }

    /** 口令哈希只在登录校验时读一次，不进 AppUser，避免身份对象在内存里长期带着哈希。 */
    public Optional<String> findPasswordHash(String username) {
        return jdbc.query("select password_hash from app_user where username = ?",
                (rs, rowNum) -> rs.getString(1), username).stream().findFirst();
    }

    public void insert(String username, String displayName, String roleCode, String deptCode, String passwordHash) {
        jdbc.update("""
                insert into app_user (username, display_name, role_code, dept_code, password_hash, enabled)
                values (?, ?, ?, ?, ?, true)
                """, username, displayName, roleCode, deptCode, passwordHash);
    }

    public void markLogin(long id) {
        jdbc.update("update app_user set last_login_at = now() where id = ?", id);
    }

    public void updatePassword(String username, String passwordHash) {
        jdbc.update("update app_user set password_hash = ? where username = ?", passwordHash, username);
    }
}
