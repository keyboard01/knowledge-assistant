package io.github.keyboard01.assistant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S5：Key 自身范围是"账号可见 ∩ Key 绑定"里的后一半，判定必须 fail-closed。 */
class KeyScopeTest {

    @Test
    @DisplayName("不限定（NONE / 空集合）：跟随绑定账号，任何域都放行")
    void noneAllowsEverything() {
        assertTrue(KeyScope.NONE.allowsDomain("projA-docs", "projA"));
        assertTrue(new KeyScope(Set.of(), Set.of(), null).allowsDomain("anything", null));
        assertFalse(KeyScope.NONE.restricts());
    }

    @Test
    @DisplayName("绑租户：域租户命中放行，越租户拒绝")
    void tenantBinding() {
        KeyScope scope = new KeyScope(Set.of("projA"), Set.of(), null);
        assertTrue(scope.restricts());
        assertTrue(scope.allowsDomain("projA-docs", "projA"));
        assertFalse(scope.allowsDomain("projB-docs", "projB"));
    }

    @Test
    @DisplayName("绑域：只认名单内的域，即使租户相同")
    void domainBinding() {
        KeyScope scope = new KeyScope(Set.of(), Set.of("projA-docs"), null);
        assertTrue(scope.allowsDomain("projA-docs", "projA"));
        assertFalse(scope.allowsDomain("projA-other", "projA"));
    }

    @Test
    @DisplayName("租户+域同时绑：两者都要过")
    void bothBindingsMustPass() {
        KeyScope scope = new KeyScope(Set.of("projA"), Set.of("projA-docs"), null);
        assertTrue(scope.allowsDomain("projA-docs", "projA"));
        assertFalse(scope.allowsDomain("projA-other", "projA"), "域不在名单");
        assertFalse(scope.allowsDomain("projB-docs", "projB"), "租户不在名单");
    }

    @Test
    @DisplayName("fail-closed：域的租户缺失一律拒绝，不猜")
    void missingTenantIsRejected() {
        assertFalse(new KeyScope(Set.of("projA"), Set.of(), null).allowsDomain("some-docs", null));
        assertFalse(new KeyScope(Set.of("projA"), Set.of(), null).allowsDomain("some-docs", " "));
    }

    @Test
    @DisplayName("编码清洗：去空白、去空串；弱断言空白落 null")
    void normalization() {
        KeyScope scope = new KeyScope(
                new java.util.HashSet<>(java.util.Arrays.asList(" projA ", "", null)), Set.of(), "  ");
        assertEquals(Set.of("projA"), scope.tenantCodes());
        assertNull(scope.endUser());
    }

    @Test
    @DisplayName("describe：空绑定显示「不限」，便于账本里人读")
    void describeShowsUnlimited() {
        assertTrue(KeyScope.NONE.describe().contains("(不限)"));
        assertTrue(new KeyScope(Set.of("projA"), Set.of(), "zhang").describe().contains("projA"));
    }
}
