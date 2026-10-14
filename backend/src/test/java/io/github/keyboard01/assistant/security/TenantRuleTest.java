package io.github.keyboard01.assistant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租户可见性规则（多项目接入 S2）。
 *
 * <p>这是整条防线的判定核心，坏起来的方式只有一个：**判定写成"永远放行"**——
 * 那样 A 租户的公开库会出现在 B 租户的检索结果里，不报错、只在数据上表现出来。
 * 所以这里的用例都盯着"什么情况下必须拒绝"。</p>
 */
class TenantRuleTest {

    private static final AccessSubject ADMIN = AccessSubject.of("admin", "admin", null);
    private static final AccessSubject OPS = AccessSubject.of("zhang.san", "ops", "D01");
    private static final AccessSubject TENANT_ADMIN = AccessSubject.of("wang", "tenant_admin", null);

    @Test
    @DisplayName("平台管理员跨租户：不加谓词、不受租户限制")
    void platformAdminCrossesTenants() {
        assertTrue(TenantRule.isPlatformAdmin(ADMIN));
        assertFalse(TenantRule.applies(ADMIN));
        assertEquals(0, TenantRule.params(ADMIN).length);
        assertTrue(TenantRule.allows(ADMIN, "任意租户", Set.of()));
        assertTrue(TenantRule.allows(ADMIN, null, Set.of()), "管理员即使域没有租户也放行");
    }

    @Test
    @DisplayName("租户管理员不是平台管理员：照样受租户限制（S3 才引入，但这条边界现在就要钉住）")
    void tenantAdminIsNotPlatformAdmin() {
        assertFalse(TenantRule.isPlatformAdmin(TENANT_ADMIN));
        assertTrue(TenantRule.applies(TENANT_ADMIN));
        assertFalse(TenantRule.allows(TENANT_ADMIN, "projB", Set.of("projA")));
        assertTrue(TenantRule.allows(TENANT_ADMIN, "projA", Set.of("projA")));
    }

    @Test
    @DisplayName("普通身份：只有域所属租户在它所属租户里才放行")
    void onlyOwnTenantsPass() {
        assertTrue(TenantRule.allows(OPS, "projA", Set.of("projA", "projB")));
        assertFalse(TenantRule.allows(OPS, "projC", Set.of("projA", "projB")));
        // 归属为空 = 什么都看不见（fail-closed），不是"不过滤"
        assertFalse(TenantRule.allows(OPS, "projA", Set.of()));
        assertFalse(TenantRule.allows(OPS, "projA", null));
    }

    @Test
    @DisplayName("域没有租户时一律拒绝：宁可不显示，也不要猜一个租户出来")
    void missingTenantCodeIsDenied() {
        assertFalse(TenantRule.allows(OPS, null, Set.of("projA")));
        assertFalse(TenantRule.allows(OPS, "  ", Set.of("projA")));
    }

    @Test
    @DisplayName("下推的 SQL：按文档所属域判租户，且只绑一个占位符（用户名）")
    void sqlShape() {
        String sql = TenantRule.sql("d");
        assertTrue(sql.contains("tenant_member"), sql);
        assertTrue(sql.contains("d.domain_code"), sql);
        assertTrue(sql.contains("tm.tenant_code = td.tenant_code"), sql);
        assertEquals(1, sql.split("\\?", -1).length - 1, "只该有一个占位符：" + sql);
        assertEquals(List.of("zhang.san"), List.of(TenantRule.params(OPS)));
    }

    @Test
    @DisplayName("无用户名的身份不加谓词（它到不了这里，加进去只会让 SQL 难懂）")
    void blankUsernameSkipsPredicate() {
        AccessSubject blank = new AccessSubject("", Set.of("viewer"), null);
        assertFalse(TenantRule.applies(blank));
        assertEquals(0, TenantRule.params(blank).length);
        // 没有身份 ⇒ 查不出任何租户归属 ⇒ 空集合 ⇒ 看不见（fail-closed 的另一半在 TenantRepository.tenantsOf）
        assertFalse(TenantRule.allows(blank, "projA", Set.of()));
    }
}
