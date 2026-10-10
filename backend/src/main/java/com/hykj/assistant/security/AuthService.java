package com.hykj.assistant.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 登录与会话。P1 用本地账号 + 服务端会话表：
 * 令牌明文只返回给浏览器一次，库里只存 SHA-256，注销即删行（可即时撤销，不像 JWT 要等过期）。
 */
@Service
public class AuthService {

    /** 本地账号起步阶段不做"记住我"：12 小时后必须重新登录。 */
    public static final Duration SESSION_TTL = Duration.ofHours(12);

    /** 口令长度下限。本地账号没有短信/邮件找回，只能靠"够长"来兜底。 */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String BAD_CREDENTIALS = "用户名或密码不正确";

    public record UserView(long id, String username, String displayName, String roleCode, String deptCode) {

        public static UserView of(AppUser user) {
            return new UserView(user.id(), user.username(), user.displayName(), user.roleCode(), user.deptCode());
        }
    }

    public record LoginResult(String token, Instant expiresAt, UserView user) {
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordHasher passwordHasher;

    public AuthService(UserRepository users, SessionRepository sessions, PasswordHasher passwordHasher) {
        this.users = users;
        this.sessions = sessions;
        this.passwordHasher = passwordHasher;
    }

    public LoginResult login(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            throw new InvalidCredentialsException(BAD_CREDENTIALS);
        }
        Optional<AppUser> found = users.findByUsername(name);
        if (found.isEmpty() || !found.get().enabled()) {
            log.warn("登录失败 username={}：账号不存在或已停用", name);
            throw new InvalidCredentialsException(BAD_CREDENTIALS);
        }
        String storedHash = users.findPasswordHash(name).orElse("");
        if (!passwordHasher.matches(rawPassword, storedHash)) {
            log.warn("登录失败 username={}：密码不正确", name);
            throw new InvalidCredentialsException(BAD_CREDENTIALS);
        }

        AppUser user = found.get();
        sessions.deleteExpired();
        String token = SessionToken.newToken();
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        sessions.insert(SessionToken.hash(token), user.id(), expiresAt);
        users.markLogin(user.id());
        log.info("登录成功 username={} role={} 有效期至 {}", user.username(), user.roleCode(), expiresAt);
        return new LoginResult(token, expiresAt, UserView.of(user));
    }

    public Optional<AppUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findActive(SessionToken.hash(token.trim()))
                .flatMap(row -> users.findById(row.userId()))
                .filter(AppUser::enabled);
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessions.delete(SessionToken.hash(token.trim()));
    }

    /**
     * 自助改密。必须验证旧密码——令牌被盗时不能让攻击者直接把主人锁在门外。
     * 改完作废该账号的其它会话，当前这一个保留。
     */
    public void changePassword(String username, String currentToken, String oldPassword, String newPassword) {
        AppUser user = users.findByUsername(username)
                .filter(AppUser::enabled)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在或已停用"));
        String storedHash = users.findPasswordHash(username).orElse("");
        if (!passwordHasher.matches(oldPassword, storedHash)) {
            log.warn("改密失败 username={}：旧密码不正确", username);
            throw new IllegalArgumentException("旧密码不正确");
        }
        if (newPassword == null || newPassword.trim().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        users.updatePassword(username, passwordHasher.hash(newPassword));
        if (currentToken != null && !currentToken.isBlank()) {
            sessions.deleteByUserExcept(user.id(), SessionToken.hash(currentToken.trim()));
        }
        log.info("已修改密码并作废该账号其它会话 username={}", username);
    }

    public List<UserView> listUsers() {
        return users.findAll().stream().map(UserView::of).toList();
    }
}
