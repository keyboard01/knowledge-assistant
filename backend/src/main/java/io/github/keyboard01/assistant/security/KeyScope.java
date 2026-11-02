package io.github.keyboard01.assistant.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * API Key 的自身范围（多项目接入 S5）：这把 Key 允许碰哪些租户、哪些知识域。
 *
 * <p>Key 的可见范围 = <b>绑定账号的可见范围 ∩ Key 自身绑定</b>。账号侧的判定照旧走
 * {@link TenantRule} + {@link AccessFilter}，这里只负责"再收窄一层"——绑定的账号可能横跨多个
 * 租户（A 项目的 Key 绑到一个人的账号上，而这个人同时属于 A 和 B），Key 绑定保证它只碰 A。</p>
 *
 * <p>两个绑定都用<b>空集合表达"不限"</b>：默认沿用绑定账号的全部可见范围，显式绑定才收窄。
 * 判定一律 fail-closed：域的租户查不出来（理论上不可能，V2 已设 NOT NULL）就拒绝，
 * 宁可误拒也不猜。</p>
 *
 * <p>{@code endUser} 是终端用户弱断言（方案 §6.2）：外部项目经 {@code X-End-User} 头传来的
 * 发起人标识，<b>只入账本、不判权</b>——判权仍走 Key 绑定的账号。跟着 KeyScope 走是因为
 * 它只在"代表项目"的调用里才有意义，会话登录的用户本身就是终端用户。</p>
 *
 * <p>纯函数：不依赖数据库与 Spring，能被单测直接覆盖。</p>
 */
public record KeyScope(Set<String> tenantCodes, Set<String> domainCodes, String endUser) {

    /** 不限范围：沿用绑定账号的可见范围（库里的空数组落到这里）。 */
    public static final KeyScope NONE = new KeyScope(Set.of(), Set.of(), null);

    public KeyScope {
        tenantCodes = normalized(tenantCodes);
        domainCodes = normalized(domainCodes);
        endUser = endUser == null || endUser.isBlank() ? null : endUser.trim();
    }

    /** 是否在收窄范围：false 表示这把 Key 跟着绑定账号走，不做额外限制。 */
    public boolean restricts() {
        return !tenantCodes.isEmpty() || !domainCodes.isEmpty();
    }

    /**
     * 内存判定：这把 Key 能不能访问目标域。与 {@link AccessFilter} 里织入的 SQL 谓词表达同一条规则
     * ——SQL 管"不带域参数的列表/检索不漏"，这里管"显式指名的域直接 403"。
     */
    public boolean allowsDomain(String domainCode, String tenantCode) {
        if (!restricts()) {
            return true;
        }
        if (domainCode == null || domainCode.isBlank()) {
            return false;
        }
        if (!domainCodes.isEmpty() && !domainCodes.contains(domainCode.trim())) {
            return false;
        }
        if (tenantCodes.isEmpty()) {
            return true;
        }
        // 域的租户缺失就拒绝：fail-closed，不猜。
        return tenantCode != null && !tenantCode.isBlank() && tenantCodes.contains(tenantCode.trim());
    }

    /** 账本/日志用的简述，两个绑定都展示（空=不限）。 */
    public String describe() {
        return "tenants=" + (tenantCodes.isEmpty() ? "(不限)" : tenantCodes)
                + " domains=" + (domainCodes.isEmpty() ? "(不限)" : domainCodes)
                + " endUser=" + (endUser == null ? "(无)" : endUser);
    }

    private static Set<String> normalized(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                cleaned.add(code.trim());
            }
        }
        return Set.copyOf(cleaned);
    }
}
