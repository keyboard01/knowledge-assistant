package io.github.keyboard01.assistant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 组织同步的映射规则：永不给 admin、撞名跳过、用户名确定性、去重、默认角色。 */
class OrgSyncPlanTest {

    private static OrgSyncPlan.DingUser user(String id, String name, String dept, boolean active) {
        return new OrgSyncPlan.DingUser(id, name, dept, active);
    }

    @Test
    @DisplayName("永不给 admin：默认角色填 admin 时降为 ops")
    void neverGrantsAdmin() {
        List<OrgSyncPlan.Upsert> plan = OrgSyncPlan.plan(List.of(user("u1", "张三", "10", true)), Set.of(), "admin");
        assertEquals(1, plan.size());
        assertEquals("ops", plan.get(0).roleCode());
    }

    @Test
    @DisplayName("用户名按外部 id 确定性生成：dt_<id>")
    void deterministicUsername() {
        assertEquals("dt_manager1234", OrgSyncPlan.username("manager1234"));
    }

    @Test
    @DisplayName("含非法字符的 id 会被清洗后再前缀")
    void usernameSanitised() {
        assertEquals("dt_a1b2", OrgSyncPlan.username("a*1/b2"));
    }

    @Test
    @DisplayName("撞本地账号名 → 跳过")
    void reservedUsernameSkipped() {
        String username = OrgSyncPlan.username("u1");
        List<OrgSyncPlan.Upsert> plan = OrgSyncPlan.plan(List.of(user("u1", "张三", "10", true)),
                Set.of(username), "ops");
        assertTrue(plan.isEmpty());
    }

    @Test
    @DisplayName("同一人挂在多个部门 → 只处理一次")
    void dedupesByUserId() {
        List<OrgSyncPlan.Upsert> plan = OrgSyncPlan.plan(
                List.of(user("u1", "张三", "10", true), user("u1", "张三", "20", true)), Set.of(), "ops");
        assertEquals(1, plan.size());
    }

    @Test
    @DisplayName("显示名缺省时回落为用户名；部门与启用状态透传")
    void displayFallbackAndFields() {
        List<OrgSyncPlan.Upsert> plan = OrgSyncPlan.plan(List.of(user("u9", "  ", "88", false)), Set.of(), "viewer");
        assertEquals(1, plan.size());
        assertEquals("dt_u9", plan.get(0).username());
        assertEquals("dt_u9", plan.get(0).displayName());
        assertEquals("88", plan.get(0).deptCode());
        assertEquals("viewer", plan.get(0).roleCode());
        assertEquals(false, plan.get(0).enabled());
    }

    @Test
    @DisplayName("空 id 的用户被忽略")
    void blankIdIgnored() {
        assertTrue(OrgSyncPlan.plan(List.of(user("  ", "x", "1", true)), Set.of(), "ops").isEmpty());
    }
}
