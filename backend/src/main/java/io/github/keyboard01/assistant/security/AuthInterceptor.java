package io.github.keyboard01.assistant.security;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录拦截：/api/** 默认拒绝，只有登录接口开放（在 WebConfig 里排除）。
 * 顺带处理 {@link RequireRole}：登录只说明"你是谁"，角色才决定"你能做什么"。
 * 身份写入 {@link CurrentUser}，请求结束务必清理，避免线程池串号。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final AuthService authService;
    private final ApiKeyService apiKeyService;

    public AuthInterceptor(AuthService authService, ApiKeyService apiKeyService) {
        this.authService = authService;
        this.apiKeyService = apiKeyService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检不带令牌，必须放行，否则浏览器里的跨域请求全挂
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 先按会话令牌解析；失败后才试对外 API Key（浏览器请求一次都不会走到那一步）。
        AppUser user = authService.resolve(tokenOf(request)).orElse(null);
        if (user == null && !isAuthEndpoint(request)) {
            user = resolveApiKey(request);
        }
        if (user == null) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或会话已过期，请重新登录");
            return false;
        }
        CurrentUser.set(user);
        String[] required = requiredRoles(handler);
        if (required.length > 0 && !hasAnyRole(user, required)) {
            write(response, HttpServletResponse.SC_FORBIDDEN,
                    "当前角色（" + user.roleCode() + "）没有执行该操作的权限");
            return false;
        }
        return true;
    }

    /**
     * 外部调用：{@code X-API-Key} 头优先，其次把同一凭据（{@code Authorization: Bearer <key>}）当密钥试一次
     * ——MCP 客户端习惯用 Bearer 传密钥。
     */
    private AppUser resolveApiKey(HttpServletRequest request) {
        String explicit = request.getHeader("X-API-Key");
        if (explicit != null && !explicit.isBlank()) {
            return apiKeyService.resolve(explicit.trim()).orElse(null);
        }
        return apiKeyService.resolve(tokenOf(request)).orElse(null);
    }

    /**
     * {@code /api/auth/**} 不接受 API Key：{@code changePassword} 会把请求凭据当作"当前 token"
     * 用于作废该账号的其它会话，拿 key 走这条路会误删账号的全部真实会话。
     */
    private static boolean isAuthEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/auth/");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        CurrentUser.clear();
    }

    /** 令牌优先取 Authorization: Bearer，其次退化到 X-Session-Token（curl 与 SSE 更方便）。 */
    public static String tokenOf(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String trimmed = header.trim();
            if (trimmed.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                return trimmed.substring(BEARER.length()).trim();
            }
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        String fallback = request.getHeader("X-Session-Token");
        return fallback == null ? null : fallback.trim();
    }

    private static String[] requiredRoles(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return new String[0];
        }
        RequireRole annotation = method.getMethodAnnotation(RequireRole.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequireRole.class);
        }
        return annotation == null ? new String[0] : annotation.value();
    }

    private static boolean hasAnyRole(AppUser user, String[] required) {
        for (String role : required) {
            if (user.hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    private static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
