package io.github.keyboard01.assistant.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 可见性过滤条件（T06 的"过滤条件生成"）。
 *
 * <p>同一套规则有两种落地方式，必须是同一份语义——否则会出现"列表里看不到、检索却搜得到"：</p>
 * <ul>
 *   <li>本地文档表：{@link #sql()} 是可下推的 exists 子查询，值全部走占位符绑定；</li>
 *   <li>检索命中：反查 document 行后按 {@link #allows(List)} 判定。</li>
 * </ul>
 *
 * <p><b>租户谓词（多项目接入 S2）就织在 {@link #sql()} 里</b>，而不是让每个调用方自己拼。
 * 理由：这条链路有 8 条 SQL（检索两路 + 被挡计数、找图三路、文档列表两条 + 可见数），
 * 漏掉任何一条的表现都是"A 租户的公开库出现在 B 租户结果里"——不报错、只在数据上表现出来。
 * 织进这里之后，"用到 {@code filter.sql()} 的查询自动带租户范围"，漏写变成不可能。
 * 平台管理员不追加（跨租户可见），见 {@link TenantRule#applies}。</p>
 */
public record AccessFilter(AccessSubject subject, String sql, List<Object> params) {

    public static final String DEFAULT_ALIAS = "d";

    public AccessFilter {
        params = List.copyOf(params);
    }

    /** 生产入口：Key 的自身范围从请求上下文取（多项目接入 S5），调用方不必逐处传。 */
    public static AccessFilter of(AccessSubject subject, String documentAlias) {
        return of(subject, documentAlias, CurrentKeyScope.get());
    }

    /**
     * 纯入口：Key 范围显式传入，供单测与无请求上下文的场景。生产一律走两参重载。
     */
    public static AccessFilter of(AccessSubject subject, String documentAlias, KeyScope keyScope) {
        String alias = documentAlias == null || documentAlias.isBlank() ? DEFAULT_ALIAS : documentAlias.trim();
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        sql.append("exists (select 1 from document_acl a where a.document_id = ")
                .append(alias)
                .append(".id and upper(a.permission) = 'READ' and (");
        sql.append("(upper(a.subject_type) = 'ALL' and (a.subject_value = '*' or a.subject_value = ''))");
        for (String role : sortedRoles(subject)) {
            sql.append(" or (upper(a.subject_type) = 'ROLE' and a.subject_value = ?)");
            params.add(role);
        }
        if (!subject.username().isEmpty()) {
            sql.append(" or (upper(a.subject_type) = 'USER' and a.subject_value = ?)");
            params.add(subject.username());
        }
        if (!subject.deptCode().isEmpty()) {
            sql.append(" or (upper(a.subject_type) = 'DEPT' and a.subject_value = ?)");
            params.add(subject.deptCode());
        }
        sql.append("))");
        // 租户范围：文档所属域的租户必须是当前用户所属租户之一（平台管理员跳过）。
        // 它和文档级 ACL 是"与"的关系，所以 ALL:* 的语义自然收窄成"本租户全员"。
        if (TenantRule.applies(subject)) {
            sql.append(" and ").append(TenantRule.sql(alias));
            for (Object param : TenantRule.params(subject)) {
                params.add(param);
            }
        }
        // Key 自身范围（多项目接入 S5）：与租户谓词同一条哲学——织进这一处，
        // 用到 filter.sql() 的 8 条查询自动带 Key 约束，漏写变成不可能。
        // 账号可见 ∩ Key 绑定：账号侧放不出来的东西这里也放不出来，这里只是再收窄。
        if (keyScope != null && keyScope.restricts()) {
            sql.append(" and exists (select 1 from knowledge_domain kd where kd.code = ")
                    .append(alias).append(".domain_code");
            if (!keyScope.domainCodes().isEmpty()) {
                sql.append(" and kd.code in (").append(placeholders(keyScope.domainCodes().size())).append(")");
                params.addAll(keyScope.domainCodes());
            }
            if (!keyScope.tenantCodes().isEmpty()) {
                sql.append(" and kd.tenant_code in (").append(placeholders(keyScope.tenantCodes().size())).append(")");
                params.addAll(keyScope.tenantCodes());
            }
            sql.append(")");
        }
        return new AccessFilter(subject, sql.toString(), params);
    }

    /** 内存判定：给检索命中反查出来的 document 行用。与域授权共用 {@link AclRule#grantsAny}。 */
    public boolean allows(List<AclRule> rules) {
        return AclRule.grantsAny(subject, rules);
    }

    public Object[] paramsArray() {
        return params.toArray();
    }

    /**
     * 账本里存的"可读过滤条件"（AC-P1-011 要求记录过滤条件）。
     *
     * <p>存的是"按什么可见范围检索的"：主体三要素 + 下推时用的 SQL 形状。
     * 参数值就是主体三要素推导出来的，不另外重复存一遍，免得两处不一致。</p>
     */
    public String describe() {
        return "user=" + (subject.username().isEmpty() ? "(无)" : subject.username())
                + " roles=" + sortedRoles(subject)
                + " dept=" + (subject.deptCode().isEmpty() ? "(无)" : subject.deptCode())
                + " sql=" + sql;
    }

    /** 占位符串：n=3 → "?,?,?"。绑定量随 Key 绑定走，量小，不需要 any(array) 的写法。 */
    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /** 角色顺序影响 SQL 里的占位符顺序，排序是为了让生成结果稳定、可断言。 */
    private static Set<String> sortedRoles(AccessSubject subject) {
        Set<String> sorted = new TreeSet<>();
        for (String role : subject.roles()) {
            if (role != null && !role.isBlank()) {
                sorted.add(role.trim());
            }
        }
        return sorted;
    }
}
