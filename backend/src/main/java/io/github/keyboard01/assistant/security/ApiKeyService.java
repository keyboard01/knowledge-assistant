package io.github.keyboard01.assistant.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * 对外 API Key。密钥与登录令牌同规格（256 位随机 + SHA-256），复用 {@link SessionToken}。
 *
 * <p><b>权限不在这里另立一套</b>：key 只决定"你是谁"（绑定账号），可见范围仍由该账号的角色/部门经
 * {@link AccessFilter} 判定。因此撤销权限只需改账号或 ACL，不必同步 key。</p>
 */
@Service
public class ApiKeyService {

    /** 创建结果：明文 key 只在这一刻返回一次，之后库里只剩哈希。 */
    public record Created(String rawKey, ApiKeyView view) {
    }

    /** 列表视图：**永远不含明文 key，只含前缀**（够人认出是哪把）。 */
    public record ApiKeyView(long id, String name, String keyPrefix, long userId, String username,
                             String displayName, boolean enabled, String createdBy, Instant createdAt,
                             Instant lastUsedAt) {
    }

    /** last_used_at 的节流窗口：热路径上不必每次请求都写库。 */
    private static final long TOUCH_INTERVAL_SECONDS = 60;

    private final ApiKeyRepository repository;
    private final UserRepository users;

    public ApiKeyService(ApiKeyRepository repository, UserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    public Created create(String name, long userId, String createdBy) {
        String keyName = name == null ? "" : name.trim();
        if (keyName.isEmpty()) {
            throw new IllegalArgumentException("请给这枚 Key 起个名字");
        }
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("绑定的账号不存在：" + userId));
        if (!user.enabled()) {
            throw new IllegalArgumentException("绑定的账号已停用：" + user.username());
        }
        String rawKey = SessionToken.newToken();
        String prefix = "ka_" + rawKey.substring(0, 8);
        long id = repository.insert(keyName, prefix, SessionToken.hash(rawKey), userId, createdBy);
        return new Created(rawKey, toView(repository.findById(id).orElseThrow()));
    }

    public List<ApiKeyView> list() {
        return repository.findAll().stream().map(this::toView).toList();
    }

    public ApiKeyView setEnabled(long id, boolean enabled) {
        repository.findById(id).orElseThrow(() -> new IllegalArgumentException("API Key 不存在：" + id));
        repository.setEnabled(id, enabled);
        return toView(repository.findById(id).orElseThrow());
    }

    public void revoke(long id) {
        repository.findById(id).orElseThrow(() -> new IllegalArgumentException("API Key 不存在：" + id));
        repository.revoke(id);
    }

    /**
     * 用密钥换身份。**任何异常或表缺失都返回 empty**——降级成 401，绝不 500。
     * 这层兜底保护的是登录与 SSE 路径：本方法在会话解析失败后才会被调用，不能因为 key 表的问题
     * 把一次本该 401 的匿名请求变成 500。
     */
    public Optional<AppUser> resolve(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<ApiKeyRepository.Row> found = repository.findActiveByHash(SessionToken.hash(rawKey.trim()));
            if (found.isEmpty()) {
                return Optional.empty();
            }
            ApiKeyRepository.Row key = found.get();
            Optional<AppUser> user = users.findById(key.userId()).filter(AppUser::enabled);
            if (user.isPresent()) {
                touchThrottled(key);
            }
            return user;
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void touchThrottled(ApiKeyRepository.Row key) {
        Instant last = key.lastUsedAt();
        if (last != null && last.isAfter(Instant.now().minusSeconds(TOUCH_INTERVAL_SECONDS))) {
            return;
        }
        try {
            repository.touchUsed(key.id());
        } catch (Exception ignore) {
            // 记账失败不影响鉴权
        }
    }

    private ApiKeyView toView(ApiKeyRepository.Row row) {
        AppUser user = users.findById(row.userId()).orElse(null);
        return new ApiKeyView(row.id(), row.name(), row.keyPrefix(), row.userId(),
                user == null ? null : user.username(),
                user == null ? null : user.displayName(),
                row.enabled(), row.createdBy(), row.createdAt(), row.lastUsedAt());
    }
}
