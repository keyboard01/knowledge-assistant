package io.github.keyboard01.assistant.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.tenant.TenantRepository;

/**
 * 管理员侧的账号维护（增删改：建号、改角色/部门、停用、重置密码）。
 * 与 {@link AuthService}（登录/会话）分开：这边管"账号是什么"，那边管"谁在登录"。
 *
 * 两条硬规则：
 * 1. 不能停用或降级**最后一个**启用管理员，否则没人能再进管理端；
 * 2. 停用或重置密码后，立即作废该账号全部会话（不等令牌自然过期）。
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final SessionRepository sessions;
    private final PasswordHasher passwordHasher;
    private final TenantRepository tenants;

    public UserAdminService(UserRepository users, RoleRepository roles, SessionRepository sessions,
                            PasswordHasher passwordHasher, TenantRepository tenants) {
        this.users = users;
        this.roles = roles;
        this.sessions = sessions;
        this.passwordHasher = passwordHasher;
        this.tenants = tenants;
    }

    /** 列表带上租户归属与租户管理员归属：管理员要能看出"这个人能看哪些项目、能管哪些项目"。 */
    public List<AuthService.UserView> list() {
        Map<Long, List<String>> memberships = tenants.membershipsByUser();
        Map<Long, List<String>> admins = tenants.adminsByUser();
        return users.findAll().stream()
                .map(user -> AuthService.UserView.of(user,
                        memberships.getOrDefault(user.id(), List.of()),
                        admins.getOrDefault(user.id(), List.of())))
                .toList();
    }

    public List<RoleRepository.RoleView> listRoles() {
        return roles.findAll();
    }

    public List<String> listTenants() {
        return tenants.allCodes();
    }

    /**
     * 建号。
     *
     * <p>{@code tenantCodes} 决定这个人能看见哪些知识域；{@code adminTenantCodes}（必须是其子集）
     * 决定他在哪些租户里是**管理员**（能管该租户的域/授权/审核）。留空落默认租户。</p>
     */
    public AuthService.UserView create(String username, String displayName, String roleCode, String deptCode,
                                       String password, List<String> tenantCodes, List<String> adminTenantCodes) {
        String name = trimToNull(username);
        if (name == null) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String role = requireRole(roleCode);
        if (password == null || password.trim().length() < AuthService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + AuthService.MIN_PASSWORD_LENGTH + " 位");
        }
        if (users.findByUsername(name).isPresent()) {
            throw new IllegalArgumentException("用户名已存在：" + name);
        }
        List<String> wanted = requireTenants(tenantCodes);
        Set<String> admins = requireAdminSubset(wanted, adminTenantCodes);
        users.insert(name, trimToNull(displayName), role, trimToNull(deptCode), passwordHasher.hash(password));
        AppUser created = users.findByUsername(name)
                .orElseThrow(() -> new IllegalStateException("创建用户后读取失败：" + name));
        tenants.replaceForUser(created.id(), wanted, admins);
        log.info("已创建用户 {}（角色 {}），租户 {}，管理 {}", name, role, wanted, admins);
        return AuthService.UserView.of(created, wanted, admins.stream().toList());
    }

    /**
     * 改资料/角色/部门/启用状态，可选改租户归属与管理权。
     *
     * <p>{@code tenantCodes} 为 null 表示"不动归属"（前端没提交这个字段时不该顺手清空）；
     * 给了就整份替换，{@code adminTenantCodes} 随之替换（必须是 {@code tenantCodes} 的子集）。</p>
     */
    public AuthService.UserView update(long id, String displayName, String roleCode, String deptCode,
                                       Boolean enabled, List<String> tenantCodes, List<String> adminTenantCodes) {
        AppUser current = users.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在：" + id));
        String role = trimToNull(roleCode) == null ? current.roleCode() : requireRole(roleCode);
        boolean willBeEnabled = enabled == null ? current.enabled() : enabled;
        String name = displayName == null || displayName.isBlank() ? current.displayName() : displayName.trim();
        String dept = deptCode == null ? current.deptCode() : trimToNull(deptCode);

        boolean losingAdmin = current.enabled() && current.hasRole("admin")
                && (!willBeEnabled || !"admin".equalsIgnoreCase(role));
        if (losingAdmin && users.countEnabledAdmins() <= 1) {
            throw new IllegalArgumentException("不能停用或降级最后一个管理员");
        }

        if (tenantCodes != null) {
            List<String> wanted = requireTenants(tenantCodes);
            Set<String> admins = requireAdminSubset(wanted, adminTenantCodes);
            guardLastTenantAdmin(current, admins);
            tenants.replaceForUser(id, wanted, admins);
        }
        users.update(id, name, role, dept, willBeEnabled);
        if (current.enabled() && !willBeEnabled) {
            sessions.deleteByUser(id);
        }
        return users.findById(id)
                .map(user -> AuthService.UserView.of(user,
                        List.copyOf(tenants.tenantsOf(user.username())),
                        List.copyOf(tenants.administeredTenants(user.username()))))
                .orElseThrow();
    }

    public void resetPassword(long id, String newPassword) {
        users.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在：" + id));
        if (newPassword == null || newPassword.trim().length() < AuthService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("新密码至少 " + AuthService.MIN_PASSWORD_LENGTH + " 位");
        }
        users.resetPassword(id, passwordHasher.hash(newPassword));
        sessions.deleteByUser(id);
    }

    private String requireRole(String roleCode) {
        String role = trimToNull(roleCode);
        if (role == null || !roles.exists(role)) {
            throw new IllegalArgumentException("未知角色：" + roleCode);
        }
        return role;
    }

    /**
     * 校验并归一化租户归属：留空落默认租户（单租户部署的正确答案），给了就逐个校验存在。
     *
     * <p>校验存在的意义：选错租户要给可读的 400，而不是等外键抛出难懂的 500；
     * 更要紧的是避免"填了一个不存在的租户"被静默接受——那会造出一个看不见任何东西的账号。</p>
     */
    private List<String> requireTenants(List<String> tenantCodes) {
        List<String> wanted = new ArrayList<>();
        if (tenantCodes != null) {
            for (String code : tenantCodes) {
                String trimmed = trimToNull(code);
                if (trimmed != null && !wanted.contains(trimmed)) {
                    wanted.add(trimmed);
                }
            }
        }
        if (wanted.isEmpty()) {
            wanted.add(TenantRepository.DEFAULT_TENANT);
        }
        for (String code : wanted) {
            if (!tenants.exists(code)) {
                throw new IllegalArgumentException("租户不存在：" + code);
            }
        }
        return wanted;
    }

    /** 管理员租户必须是所属租户的子集，否则"能管一个自己看不到的租户"是自相矛盾的。 */
    private Set<String> requireAdminSubset(List<String> wanted, List<String> adminTenantCodes) {
        Set<String> admins = new TreeSet<>();
        if (adminTenantCodes != null) {
            for (String code : adminTenantCodes) {
                String trimmed = trimToNull(code);
                if (trimmed != null) {
                    admins.add(trimmed);
                }
            }
        }
        for (String code : admins) {
            if (!wanted.contains(code)) {
                throw new IllegalArgumentException("管理员租户必须同时是所属租户：" + code);
            }
        }
        return admins;
    }

    /**
     * 守卫：不能把某个租户的**最后一个**管理员移除或降级——否则那个租户就没有人能管它的域/授权/审核，
     * 只能由平台管理员救。与"最后一个平台管理员不能停用"是同一类规则。
     */
    private void guardLastTenantAdmin(AppUser current, Set<String> newAdmins) {
        for (String code : tenants.administeredTenants(current.username())) {
            if (!newAdmins.contains(code) && tenants.countAdmins(code) <= 1) {
                throw new IllegalArgumentException("不能移除租户 " + code + " 的最后一个管理员");
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
