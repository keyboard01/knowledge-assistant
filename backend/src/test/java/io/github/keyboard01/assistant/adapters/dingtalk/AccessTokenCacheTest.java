package io.github.keyboard01.assistant.adapters.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** token 缓存的过期与作废行为（时钟可注入，不依赖真实时间）。 */
class AccessTokenCacheTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AccessTokenCache cache = new AccessTokenCache(now::get);

    @Test
    @DisplayName("命中且未过期 → 返回缓存 token")
    void returnsCachedToken() {
        cache.put("appKey", "token-1", 7200);
        assertEquals("token-1", cache.get("appKey"));
    }

    @Test
    @DisplayName("超过有效期（含 5 分钟安全窗口）→ 返回 null，强制重取")
    void expiresAfterTtl() {
        cache.put("appKey", "token-1", 7200);
        now.addAndGet(6900); // ttl = 7200 - 300 = 6900
        assertNull(cache.get("appKey"));
    }

    @Test
    @DisplayName("appKey 不同 → 命中失败，重新取")
    void differentAppKeyMisses() {
        cache.put("appA", "token-A", 7200);
        assertNull(cache.get("appB"));
    }

    @Test
    @DisplayName("作废后立即失效")
    void invalidate() {
        cache.put("appKey", "token-1", 7200);
        cache.invalidate();
        assertNull(cache.get("appKey"));
    }
}
