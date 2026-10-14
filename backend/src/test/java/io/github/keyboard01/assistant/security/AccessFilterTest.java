package io.github.keyboard01.assistant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 权限过滤的两条腿（内存判定 + SQL 下推）必须同一套语义，这里把它们钉住。 */
class AccessFilterTest {

    private static final AccessSubject ADMIN = AccessSubject.of("admin", "admin", null);
    private static final AccessSubject DEMO = AccessSubject.of("demo", "viewer", null);

    @Test
    @DisplayName("ALL/* 对所有身份可见")
    void allRuleGrantsEveryone() {
        List<AclRule> rules = List.of(new AclRule("ALL", "*", "READ"));
        assertTrue(AccessFilter.of(ADMIN, "d").allows(rules));
        assertTrue(AccessFilter.of(DEMO, "d").allows(rules));
    }

    @Test
    @DisplayName("ROLE 规则只对命中角色可见：秘密级文档 demo 看不到")
    void roleRuleOnlyGrantsMatchingRole() {
        List<AclRule> secret = List.of(new AclRule("ROLE", "admin", "READ"), new AclRule("ROLE", "hr", "READ"));
        assertTrue(AccessFilter.of(ADMIN, "d").allows(secret));
        assertFalse(AccessFilter.of(DEMO, "d").allows(secret));
    }

    @Test
    @DisplayName("USER / DEPT 规则按用户名与部门匹配；部门为空时不匹配任何部门规则")
    void userAndDeptRules() {
        List<AclRule> byUser = List.of(new AclRule("USER", "demo", "READ"));
        assertTrue(AccessFilter.of(DEMO, "d").allows(byUser));
        assertFalse(AccessFilter.of(ADMIN, "d").allows(byUser));

        List<AclRule> byDept = List.of(new AclRule("DEPT", "finance", "READ"));
        assertTrue(AccessFilter.of(AccessSubject.of("demo", "viewer", "finance"), "d").allows(byDept));
        assertFalse(AccessFilter.of(DEMO, "d").allows(byDept));
    }

    @Test
    @DisplayName("fail-closed：没有 ACL 行就不可见，管理员也不例外")
    void noRuleMeansInvisible() {
        assertFalse(AccessFilter.of(ADMIN, "d").allows(List.of()));
        assertFalse(AccessFilter.of(ADMIN, "d").allows(null));
    }

    @Test
    @DisplayName("非 READ 权限不产生可见性")
    void writePermissionDoesNotGrantRead() {
        assertFalse(AccessFilter.of(ADMIN, "d").allows(List.of(new AclRule("ALL", "*", "WRITE"))));
    }

    @Test
    @DisplayName("下推条件只绑定占位符，不把用户名拼进 SQL；占位符数量与参数一致")
    void sqlBindsParametersInsteadOfInterpolating() {
        AccessFilter filter = AccessFilter.of(AccessSubject.of("o'brien", "viewer", "finance"), "d");
        assertFalse(filter.sql().contains("o'brien"), "用户名不得出现在 SQL 文本里");
        // 第 4 个 o'brien 是租户谓词的参数（同一个用户名绑两次：一次判文档 ACL，一次判租户归属）
        assertEquals(List.of("viewer", "o'brien", "finance", "o'brien"), filter.params());
        assertEquals(filter.params().size(), filter.sql().split("\\?", -1).length - 1);
    }

    @Test
    @DisplayName("租户谓词：普通身份下推，平台管理员不下推（跨租户可见）")
    void tenantPredicateIsPushedDownUnlessAdmin() {
        AccessFilter demo = AccessFilter.of(DEMO, "d");
        assertTrue(demo.sql().contains("tenant_member"), "普通身份必须带租户谓词：" + demo.sql());
        assertTrue(demo.sql().contains("d.domain_code"), "租户谓词要按文档所属域判定：" + demo.sql());
        assertEquals("demo", demo.params().get(demo.params().size() - 1));

        AccessFilter admin = AccessFilter.of(ADMIN, "d");
        assertFalse(admin.sql().contains("tenant_member"), "平台管理员跨租户，不该带租户谓词：" + admin.sql());
        assertFalse(admin.sql().contains("o'brien"));
    }

    @Test
    @DisplayName("租户谓词用别名解耦：换 document 别名时条件跟着换，不会写死 'd'")
    void tenantPredicateFollowsAlias() {
        assertTrue(AccessFilter.of(DEMO, "doc").sql().contains("doc.domain_code"));
    }

    @Test
    @DisplayName("角色顺序稳定：同一身份生成的条件完全一致")
    void sqlIsDeterministic() {
        AccessSubject one = new AccessSubject("demo", Set.of("viewer", "ops"), null);
        AccessSubject two = new AccessSubject("demo", Set.of("ops", "viewer"), null);
        assertEquals(AccessFilter.of(one, "d").sql(), AccessFilter.of(two, "d").sql());
        assertEquals(AccessFilter.of(one, "d").params(), AccessFilter.of(two, "d").params());
    }

    @Test
    @DisplayName("没有角色时仍生成合法条件：至少保留 ALL 分支")
    void anonymousStillGetsAllBranch() {
        AccessFilter filter = AccessFilter.of(new AccessSubject("", Set.of(), null), "d");
        assertTrue(filter.sql().contains("'ALL'"));
        assertEquals(List.of(), filter.params());
    }
}
