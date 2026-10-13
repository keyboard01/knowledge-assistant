package io.github.keyboard01.assistant.security;

import java.util.List;

import org.springframework.stereotype.Service;

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

    private final UserRepository users;
    private final RoleRepository roles;
    private final SessionRepository sessions;
    private final PasswordHasher passwordHasher;

    public UserAdminService(UserRepository users, RoleRepository roles, SessionRepository sessions,
                            PasswordHasher passwordHasher) {
        this.users = users;
        this.roles = roles;
        this.sessions = sessions;
        this.passwordHasher = passwordHasher;
    }

    public List<AuthService.UserView> list() {
        return users.findAll().stream().map(AuthService.UserView::of).toList();
    }

    public List<RoleRepository.RoleView> listRoles() {
        return roles.findAll();
    }

    public AuthService.UserView create(String username, String displayName, String roleCode, String deptCode,
                                       String password) {
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
        users.insert(name, trimToNull(displayName), role, trimToNull(deptCode), passwordHasher.hash(password));
        return users.findByUsername(name).map(AuthService.UserView::of)
                .orElseThrow(() -> new IllegalStateException("创建用户后读取失败：" + name));
    }

    public AuthService.UserView update(long id, String displayName, String roleCode, String deptCode, Boolean enabled) {
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

        users.update(id, name, role, dept, willBeEnabled);
        if (current.enabled() && !willBeEnabled) {
            sessions.deleteByUser(id);
        }
        return users.findById(id).map(AuthService.UserView::of).orElseThrow();
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
