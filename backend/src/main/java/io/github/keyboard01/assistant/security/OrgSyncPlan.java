package io.github.keyboard01.assistant.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 组织同步的**纯规划函数**：把"钉钉侧的人"映射成"本地账号的目标状态"。
 * 不碰 DB、不碰 HTTP，所以"永不给 admin / 撞名怎么办 / 部门映射"这些规则可以被单测直接钉死。
 */
public final class OrgSyncPlan {

    /** 外部身份来源标识。 */
    public static final String SOURCE = "dingtalk";

    private static final String FALLBACK_ROLE = "ops";

    /** 钉钉侧的用户（同步输入的中间表示）。 */
    public record DingUser(String userId, String name, String deptId, boolean active) {
    }

    /** 本地账号的目标状态（一次 upsert）。 */
    public record Upsert(String externalId, String username, String displayName, String deptCode, String roleCode,
                         boolean enabled) {
    }

    private OrgSyncPlan() {
    }

    /**
     * @param reservedUsernames 本地自建账号占用、不能被同步账号撞上的用户名
     * @param defaultRole       同步账号的默认角色（admin 会被强制降为 ops）
     */
    public static List<Upsert> plan(List<DingUser> users, Set<String> reservedUsernames, String defaultRole) {
        Set<String> reserved = reservedUsernames == null ? Set.of() : reservedUsernames;
        String role = roleOrDefault(defaultRole);
        List<Upsert> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (users == null) {
            return out;
        }
        for (DingUser user : users) {
            if (user == null || user.userId() == null || user.userId().isBlank()) {
                continue;
            }
            if (!seen.add(user.userId())) {
                continue; // 同一人挂在多个部门下，只处理一次
            }
            String username = username(user.userId());
            if (reserved.contains(username)) {
                continue; // 撞到人工建的本地账号名：跳过，交由管理员人工处理
            }
            String displayName = user.name() == null || user.name().isBlank() ? username : user.name().trim();
            String deptCode = user.deptId() == null || user.deptId().isBlank() ? null : user.deptId().trim();
            out.add(new Upsert(user.userId(), username, displayName, deptCode, role, user.active()));
        }
        return out;
    }

    /** 用户名按外部 id 确定性生成——"撞名就跳过"会让某些人永远登不上，确定性命名能避开这一类。 */
    public static String username(String dingUserId) {
        String cleaned = dingUserId == null ? "" : dingUserId.replaceAll("[^A-Za-z0-9_.-]", "");
        if (cleaned.isEmpty()) {
            cleaned = "user";
        }
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        return "dt_" + cleaned;
    }

    /** 永不给 admin：外部同步不能凭空造出管理员。 */
    public static String roleOrDefault(String role) {
        String value = role == null ? "" : role.trim();
        if (value.isEmpty() || "admin".equalsIgnoreCase(value)) {
            return FALLBACK_ROLE;
        }
        return value;
    }
}
