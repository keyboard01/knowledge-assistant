package io.github.keyboard01.assistant.security;

/**
 * 当前请求的 Key 范围（多项目接入 S5）。由 {@link AuthInterceptor} 在"API Key 鉴权成功"时写入，
 * 请求结束务必清理，避免线程池串号——与 {@link CurrentUser} 同一套生命周期纪律。
 *
 * <p>会话登录（浏览器）的请求不写入：{@link #get()} 返回 {@link KeyScope#NONE}，
 * 即"不收窄"，账号自己的可见范围就是全部。</p>
 */
public final class CurrentKeyScope {

    private static final ThreadLocal<KeyScope> HOLDER = new ThreadLocal<>();

    private CurrentKeyScope() {
    }

    static void set(KeyScope scope) {
        HOLDER.set(scope == null ? KeyScope.NONE : scope);
    }

    static void clear() {
        HOLDER.remove();
    }

    /** 永不返回 null：没写过就是 {@link KeyScope#NONE}，调用方不必判空。 */
    public static KeyScope get() {
        KeyScope scope = HOLDER.get();
        return scope == null ? KeyScope.NONE : scope;
    }
}
