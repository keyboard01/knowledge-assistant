package io.github.keyboard01.assistant.tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 租户与租户成员的读写。
 *
 * <p>本片（多项目接入 S2）只需要"查某个人属于哪些租户"和"改某个人的租户归属"；
 * 租户本身的增删改（平台管理员建租户）留到 S3，那时这张仓储会长出 CRUD。</p>
 */
@Repository
public class TenantRepository {

    /** 默认租户：V2 建的那一个，升级前的域与账号都归它。 */
    public static final String DEFAULT_TENANT = "default";

    private final JdbcTemplate jdbc;

    public TenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 某个人所属的租户集合。
     *
     * <p><b>查不到就是空集</b>，而空集在可见性上的表现是"什么都看不见"（fail-closed）——
     * 这正是我们要的方向：漏配归属只会让人看不到东西，不会让人看到不该看的。</p>
     */
    public Set<String> tenantsOf(String username) {
        if (username == null || username.isBlank()) {
            return Set.of();
        }
        return new TreeSet<>(jdbc.query("""
                select tm.tenant_code
                  from tenant_member tm
                  join app_user u on u.id = tm.user_id
                 where u.username = ?
                """, (rs, rowNum) -> rs.getString("tenant_code"), username.trim()));
    }

    /** 某个人是否是某个租户的管理员（多项目接入 S3：管理权不靠全局角色，靠这条成员行）。 */
    public boolean isTenantAdmin(String username, String tenantCode) {
        if (username == null || username.isBlank() || tenantCode == null || tenantCode.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from tenant_member tm
                  join app_user u on u.id = tm.user_id
                 where u.username = ? and tm.tenant_code = ? and tm.role = 'admin'
                """, Integer.class, username.trim(), tenantCode.trim());
        return count != null && count > 0;
    }

    /** 某个人担任管理员的租户集合（前端入口与视图收窄用）。 */
    public Set<String> administeredTenants(String username) {
        if (username == null || username.isBlank()) {
            return Set.of();
        }
        return new TreeSet<>(jdbc.query("""
                select tm.tenant_code from tenant_member tm
                  join app_user u on u.id = tm.user_id
                 where u.username = ? and tm.role = 'admin'
                """, (rs, rowNum) -> rs.getString("tenant_code"), username.trim()));
    }

    /** 某个租户还有几个管理员（"最后一个租户管理员不能移除"守卫用）。 */
    public int countAdmins(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return 0;
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from tenant_member where tenant_code = ? and role = 'admin'",
                Integer.class, tenantCode.trim());
        return count == null ? 0 : count;
    }

    /** 全部租户的归属（一次查完，供用户列表展示用；这张表很小）。 */
    public Map<Long, List<String>> membershipsByUser() {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        jdbc.query("""
                select user_id, tenant_code from tenant_member order by user_id, tenant_code
                """, (rs, rowNum) -> Map.entry(rs.getLong("user_id"), rs.getString("tenant_code")))
                .forEach(entry -> out.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return out;
    }

    /** 全部租户的管理员归属（一次查完，用户列表展示"这个人管哪些租户"用）。 */
    public Map<Long, List<String>> adminsByUser() {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        jdbc.query("""
                select user_id, tenant_code from tenant_member
                 where role = 'admin' order by user_id, tenant_code
                """, (rs, rowNum) -> Map.entry(rs.getLong("user_id"), rs.getString("tenant_code")))
                .forEach(entry -> out.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return out;
    }

    /**
     * 整份替换某个人的租户归属与角色（提交什么就是什么）。
     *
     * <p>{@code adminTenantCodes} 必须是 {@code tenantCodes} 的子集（由调用方校验）；
     * 在子集里的写 {@code role='admin'}，其余写 {@code member'}。</p>
     *
     * <p>刻意不做"先全删再全插"：那样会把未变动行的角色一起抹掉——编辑一次用户资料
     * 就把租户管理员降级，是最难发现的那类错。所以按差集处理，保留未变动行的角色。</p>
     */
    public void replaceForUser(long userId, List<String> tenantCodes, Set<String> adminTenantCodes) {
        Set<String> wanted = new TreeSet<>();
        if (tenantCodes != null) {
            for (String code : tenantCodes) {
                if (code != null && !code.isBlank()) {
                    wanted.add(code.trim());
                }
            }
        }
        Set<String> admins = adminTenantCodes == null ? Set.of() : adminTenantCodes;
        // 一次把当前角色查出来，避免逐行回查
        Map<String, String> currentRoles = new LinkedHashMap<>();
        jdbc.query("select tenant_code, role from tenant_member where user_id = ?",
                (rs, rowNum) -> Map.entry(rs.getString("tenant_code"), rs.getString("role")), userId)
                .forEach(entry -> currentRoles.put(entry.getKey(), entry.getValue()));
        for (String code : currentRoles.keySet()) {
            if (!wanted.contains(code)) {
                jdbc.update("delete from tenant_member where user_id = ? and tenant_code = ?", userId, code);
            } else if ("admin".equals(currentRoles.get(code)) != admins.contains(code)) {
                jdbc.update("update tenant_member set role = ? where user_id = ? and tenant_code = ?",
                        admins.contains(code) ? "admin" : "member", userId, code);
            }
        }
        for (String code : wanted) {
            if (!currentRoles.containsKey(code)) {
                jdbc.update("""
                        insert into tenant_member (tenant_code, user_id, role) values (?, ?, ?)
                        on conflict (tenant_code, user_id) do nothing
                        """, code, userId, admins.contains(code) ? "admin" : "member");
            }
        }
    }

    /** 补一条租户归属（已存在则**不动**其角色）。种子账号与目录同步出来的账号用它。 */
    public void ensureMembership(long userId, String tenantCode, String role) {
        if (tenantCode == null || tenantCode.isBlank() || !exists(tenantCode)) {
            return;
        }
        jdbc.update("""
                insert into tenant_member (tenant_code, user_id, role) values (?, ?, ?)
                on conflict (tenant_code, user_id) do nothing
                """, tenantCode.trim(), userId, role);
    }

    public boolean exists(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject("select count(*) from tenant where code = ?", Integer.class,
                tenantCode.trim());
        return count != null && count > 0;
    }

    public List<String> allCodes() {
        return jdbc.query("select code from tenant order by code", (rs, rowNum) -> rs.getString("code"));
    }
}
