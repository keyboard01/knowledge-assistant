package io.github.keyboard01.assistant.security;

import java.util.Map;

/**
 * 钉钉配置的门面。**所有入口都先问这里**，避免"未配置"的判断散落在五处且逐渐走样。
 *
 * <p>{@code enabled} 是用户开关；{@code usable} 额外要求 appKey/appSecret 非空——
 * 打开开关但没填凭据，等同于未启用（而不是每次请求都去撞钉钉报 500）。</p>
 */
public record DingTalkConfig(boolean enabled, String appKey, String appSecret, String corpId, String agentId,
                             String rootDeptId, String defaultRole) {

    private static final String DEFAULT_ROOT_DEPT = "1";
    private static final String DEFAULT_ROLE = "ops";

    public static DingTalkConfig from(Map<String, String> cfg) {
        Map<String, String> values = cfg == null ? Map.of() : cfg;
        return new DingTalkConfig(
                Boolean.parseBoolean(text(values.get("enabled"))),
                text(values.get("appKey")),
                text(values.get("appSecret")),
                text(values.get("corpId")),
                text(values.get("agentId")),
                text(values.get("rootDeptId")).isEmpty() ? DEFAULT_ROOT_DEPT : text(values.get("rootDeptId")),
                text(values.get("defaultRole")).isEmpty() ? DEFAULT_ROLE : text(values.get("defaultRole")));
    }

    /** 真正可用：开关打开且凭据齐全。 */
    public boolean usable() {
        return enabled && !appKey.isEmpty() && !appSecret.isEmpty();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
