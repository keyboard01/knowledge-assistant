package com.hykj.assistant.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 访问主体：判断可见性真正需要的那三样东西（谁、什么角色、哪个部门）。
 * 单独成型是为了让 ACL 判断不依赖数据库与 Spring，能被单测直接覆盖。
 */
public record AccessSubject(String username, Set<String> roles, String deptCode) {

    public AccessSubject {
        username = username == null ? "" : username.trim();
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        deptCode = deptCode == null ? "" : deptCode.trim();
    }

    public static AccessSubject of(String username, String roleCode, String deptCode) {
        Set<String> roles = new LinkedHashSet<>();
        if (roleCode != null && !roleCode.isBlank()) {
            roles.add(roleCode.trim());
        }
        return new AccessSubject(username, roles, deptCode);
    }
}
