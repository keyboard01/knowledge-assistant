package io.github.keyboard01.assistant.security;

/**
 * 登录身份。P1 是单角色（app_user.role_code），所以这里只有一个 roleCode；
 * roles() 留成集合是为了 ACL 判定与将来多角色共存。
 */
public record AppUser(long id, String username, String displayName, String roleCode, String deptCode,
                      boolean enabled) {

    public AccessSubject subject() {
        return AccessSubject.of(username, roleCode, deptCode);
    }

    public boolean hasRole(String role) {
        return role != null && role.equalsIgnoreCase(roleCode);
    }
}
