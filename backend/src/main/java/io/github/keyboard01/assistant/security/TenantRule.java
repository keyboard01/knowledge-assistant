package io.github.keyboard01.assistant.security;

import java.util.Set;

/**
 * 租户可见性规则（多项目接入 S2）。**纯函数**，SQL 形状与内存判定必须出自同一处，
 * 否则又会出现"列表里看不到、检索却搜得到"那种两套语义的问题。
 *
 * <p><b>一条规则</b>：非平台管理员只能看见"自己所属租户"的域（及其文档）。</p>
 *
 * <p>{@code domain_acl} / {@code document_acl} 里的 {@code ALL:*} 因此**自动**从"平台全局所有人"
 * 收窄成"本租户全员"——因为一个用户根本走不到别的租户的域上。反过来说：ALL 的语义不需要改，
 * 它变窄是租户谓词带来的结果。这条结论很重要，它意味着 S2 不必去动 ACL 判断本身。</p>
 *
 * <p>平台管理员（角色码 {@code admin}）跨租户可见，与"admin 看全部"的既有口径一致
 * （列表、查看、审核都依赖它）。租户管理员是另一个角色，S3 才引入，**不走这条短路**。</p>
 */
public final class TenantRule {

    private static final String PLATFORM_ADMIN_ROLE = "admin";

    private TenantRule() {
    }

    /** 平台管理员：跨租户可见。判定只看角色码，与 {@code DocumentVisibility.isAdminRole} 同源。 */
    public static boolean isPlatformAdmin(AccessSubject subject) {
        return subject != null && subject.roles().stream()
                .anyMatch(role -> role != null && PLATFORM_ADMIN_ROLE.equalsIgnoreCase(role.trim()));
    }

    /**
     * 这个身份需不需要加租户谓词。
     *
     * <p>平台管理员不需要（跨租户）；用户名为空也不需要——那种身份本来就到不了这里
     * （{@code CurrentUser.require()} 在入口挡下），而给空用户名生成一个恒不命中的子查询
     * 只会让 SQL 变得难懂。</p>
     */
    public static boolean applies(AccessSubject subject) {
        return subject != null && !isPlatformAdmin(subject) && !subject.username().isEmpty();
    }

    /**
     * 可下推的租户谓词：文档所属域的租户，必须是当前用户所属租户之一。
     *
     * <p>用 {@code exists} 子查询而不是把租户列表拼进 SQL：租户归属会变，拼进去的条件一旦过期
     * 就是静默泄漏；而且用户名的值走占位符，与 {@link AccessFilter} 同一套纪律。</p>
     *
     * <p>别名 {@code tm}/{@code tu}/{@code td} 与调用方的 {@code d}/{@code a} 不冲突。</p>
     */
    public static String sql(String documentAlias) {
        String alias = documentAlias == null || documentAlias.isBlank()
                ? AccessFilter.DEFAULT_ALIAS : documentAlias.trim();
        return "exists (select 1 from tenant_member tm"
                + " join app_user tu on tu.id = tm.user_id"
                + " join knowledge_domain td on td.code = " + alias + ".domain_code"
                + " where tu.username = ? and tm.tenant_code = td.tenant_code)";
    }

    /** 与 {@link #sql} 配套的参数（占位符数量必须与它一致）。 */
    public static Object[] params(AccessSubject subject) {
        return applies(subject) ? new Object[]{subject.username()} : new Object[0];
    }

    /**
     * 内存判定：给域级门用（用户所属租户集合由调用方查一次，避免逐个域查库）。
     *
     * <p>与 {@link #sql} 表达同一条规则：平台管理员放行；否则要求"域所属租户 ∈ 用户所属租户"。
     * {@code tenantCode} 为 null（理论上不可能，V2 已把它设成 NOT NULL）一律拒绝——
     * fail-closed，宁可不显示也不要猜一个租户出来。</p>
     */
    public static boolean allows(AccessSubject subject, String tenantCode, Set<String> userTenants) {
        if (isPlatformAdmin(subject)) {
            return true;
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            return false;
        }
        return userTenants != null && userTenants.contains(tenantCode);
    }
}
