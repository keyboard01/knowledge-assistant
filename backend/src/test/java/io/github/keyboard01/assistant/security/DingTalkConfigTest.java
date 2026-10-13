package io.github.keyboard01.assistant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 配置门：留空即禁用，是最该被钉死的"安全默认"。 */
class DingTalkConfigTest {

    @Test
    @DisplayName("空配置 → 未启用且不可用")
    void blankIsDisabled() {
        DingTalkConfig cfg = DingTalkConfig.from(Map.of());
        assertFalse(cfg.enabled());
        assertFalse(cfg.usable());
    }

    @Test
    @DisplayName("打开开关但缺 appKey/appSecret → 仍不可用")
    void enabledWithoutCredentialsIsNotUsable() {
        assertFalse(DingTalkConfig.from(Map.of("enabled", "true")).usable());
        assertFalse(DingTalkConfig.from(Map.of("enabled", "true", "appKey", "k")).usable());
        assertFalse(DingTalkConfig.from(Map.of("enabled", "true", "appSecret", "s")).usable());
    }

    @Test
    @DisplayName("开关 + 凭据齐全 → 可用")
    void enabledWithCredentialsIsUsable() {
        DingTalkConfig cfg = DingTalkConfig.from(Map.of(
                "enabled", "true", "appKey", "key", "appSecret", "secret"));
        assertTrue(cfg.usable());
    }

    @Test
    @DisplayName("默认值：根部门 1，默认角色 ops")
    void defaults() {
        DingTalkConfig cfg = DingTalkConfig.from(Map.of("enabled", "true", "appKey", "k", "appSecret", "s"));
        assertEquals("1", cfg.rootDeptId());
        assertEquals("ops", cfg.defaultRole());
    }

    @Test
    @DisplayName("null 配置不抛异常")
    void nullMap() {
        assertFalse(DingTalkConfig.from(null).enabled());
    }
}
