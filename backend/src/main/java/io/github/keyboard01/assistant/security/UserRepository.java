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
        List<String> hashes = jdbc.query("select password_hash from app_user where username = ?",
                (rs, rowNum) -> rs.getString(1), username);
        // 外部同步账号 password_hash 为 null；用 ofNullable 而不是 stream().findFirst()——
        // 后者遇到 null 元素会抛 NPE（外部账号本就不允许走密码登录，这里只该返回空）。
        return hashes.isEmpty() ? Optional.empty() : Optional.ofNullable(hashes.get(0));
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

    /** 管理员改资料/角色/部门/启用状态。用户名不在这里改（它就是登录名）。 */
    public void update(long id, String displayName, String roleCode, String deptCode, boolean enabled) {
        jdbc.update("""
                update app_user set display_name = ?, role_code = ?, dept_code = ?, enabled = ? where id = ?
                """, displayName, roleCode, deptCode, enabled, id);
    }

    public void resetPassword(long id, String passwordHash) {
        jdbc.update("update app_user set password_hash = ? where id = ?", passwordHash, id);
    }

    /** 最后一个管理员守卫用：数还有几个启用状态的管理员。 */
    public int countEnabledAdmins() {
        Integer count = jdbc.queryForObject(
                "select count(*) from app_user where enabled and role_code = 'admin'", Integer.class);
        return count == null ? 0 : count;
    }

    // ---- 外部身份源（钉钉组织同步） ----

    /** 按外部标识找已映射的本地账号 id。 */
    public Optional<Long> findIdByExternal(String source, String externalId) {
        return jdbc.query("select id from app_user where external_source = ? and external_id = ?",
                (rs, rowNum) -> rs.getLong(1), source, externalId).stream().findFirst();
    }

    /** 本地自建账号（未绑定外部标识）的用户名——同步时避开，避免与人工建的账号撞名。 */
    public List<String> usernamesWithoutExternal() {
        return jdbc.queryForList("select username from app_user where external_id is null", String.class);
    }

    /** 外部身份源建号：**无密码**（password_hash 留空，故不能走密码登录）。 */
    public long insertExternal(String username, String displayName, String roleCode, String deptCode,
                               String source, String externalId, boolean enabled) {
        Long id = jdbc.queryForObject("""
                insert into app_user (username, display_name, role_code, dept_code, password_hash,
                                      enabled, external_source, external_id)
                values (?, ?, ?, ?, null, ?, ?, ?) returning id
                """, Long.class, username, displayName, roleCode, deptCode, enabled, source, externalId);
        return id == null ? 0L : id;
    }

    /** 同步更新：只改显示名/角色/部门/启用；用户名与密码不动。 */
    public void updateExternal(long id, String displayName, String roleCode, String deptCode, boolean enabled) {
        jdbc.update("""
                update app_user set display_name = ?, role_code = ?, dept_code = ?, enabled = ? where id = ?
                """, displayName, roleCode, deptCode, enabled, id);
    }
}
