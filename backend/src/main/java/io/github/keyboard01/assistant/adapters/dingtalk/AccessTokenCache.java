package io.github.keyboard01.assistant.adapters.dingtalk;

import java.util.function.LongSupplier;

/**
 * access_token 的内存缓存。钉钉 token 有效期约 7200s，但把 token 落库没有必要——
 * 进程重启重取一次即可。**只在内存里，不打印、不落盘。**
 *
 * <p>留出 5 分钟安全窗口，避免"取到 token 后立刻过期"。时钟可注入，便于单测过期与刷新。</p>
 */
public class AccessTokenCache {

    private static final long SAFETY_WINDOW_SECONDS = 300;

    private record Entry(String appKey, String token, long expiresAtEpochSeconds) {
    }

    private final LongSupplier nowSeconds;
    private Entry entry;

    public AccessTokenCache() {
        this(() -> System.currentTimeMillis() / 1000L);
    }

    public AccessTokenCache(LongSupplier nowSeconds) {
        this.nowSeconds = nowSeconds;
    }

    /** 命中且未过期返回缓存的 token；否则返回 null（调用方去取新 token 后 {@link #put}）。 */
    public synchronized String get(String appKey) {
        if (entry == null || !entry.appKey().equals(appKey)) {
            return null;
        }
        if (nowSeconds.getAsLong() >= entry.expiresAtEpochSeconds()) {
            return null;
        }
        return entry.token();
    }

    public synchronized void put(String appKey, String token, long expiresInSeconds) {
        long ttl = Math.max(1, expiresInSeconds - SAFETY_WINDOW_SECONDS);
        entry = new Entry(appKey, token, nowSeconds.getAsLong() + ttl);
    }

    /** 钉钉报 token 失效时强制作废，下次调用会重新取。 */
    public synchronized void invalidate() {
        entry = null;
    }
}
