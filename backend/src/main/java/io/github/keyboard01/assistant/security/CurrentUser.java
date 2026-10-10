package io.github.keyboard01.assistant.security;

/**
 * 当前请求的登录身份。由 AuthInterceptor 写入并在请求结束时清理。
 * 业务层（检索、生成）通过它拿身份，绝不从请求参数里读用户/角色。
 */
public final class CurrentUser {

    private static final ThreadLocal<AppUser> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    static void set(AppUser user) {
        HOLDER.set(user);
    }

    static void clear() {
        HOLDER.remove();
    }

    public static AppUser get() {
        return HOLDER.get();
    }

    public static AppUser require() {
        AppUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalStateException("当前请求没有登录身份");
        }
        return user;
    }

    public static AccessSubject subject() {
        return require().subject();
    }
}
