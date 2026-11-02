package io.github.keyboard01.assistant.security;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.domain.DomainRepository;
import io.github.keyboard01.assistant.tenant.TenantRepository;

/**
 * 对外 API Key。密钥与登录令牌同规格（256 位随机 + SHA-256），复用 {@link SessionToken}。
 *
 * <p><b>权限不在 key 上另立一套</b>：key 决定"你是谁"（绑定账号），可见范围仍由该账号的角色/部门经
 * {@link AccessFilter} 判定。因此撤销权限只需改账号或 ACL，不必同步 key。</p>
 *
 * <p>S5 起支持<b>自身范围绑定</b>（租户/域，空=不限）：绑定账号可能横跨多个租户，
 * Key 绑定把它再收窄一层，见 {@link KeyScope}。范围是"账号可见 ∩ Key 绑定"，
 * 改绑不改变账号本身的权限。</p>
 */
@Service
public class ApiKeyService {

    /** 创建结果：明文 key 只在这一刻返回一次，之后库里只剩哈希。 */
    public record Created(String rawKey, ApiKeyView view) {
    }

    /** 列表视图：**永远不含明文 key，只含前缀**（够人认出是哪把）。 */
    public record ApiKeyView(long id, String name, String keyPrefix, long userId, String username,
                             String displayName, boolean enabled, String createdBy, Instant createdAt,
                             Instant lastUsedAt, Set<String> tenantCodes, Set<String> domainCodes) {
    }

    /** 解析结果：身份 + 这把 Key 的自身范围。范围在 {@link AuthInterceptor} 落到请求上下文。 */
    public record Resolved(AppUser user, KeyScope scope) {
    }

    /** last_used_at 的节流窗口：热路径上不必每次请求都写库。 */
    private static final long TOUCH_INTERVAL_SECONDS = 60;

    private final ApiKeyRepository repository;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final DomainRepository domains;

    public ApiKeyService(ApiKeyRepository repository, UserRepository users,
                         TenantRepository tenants, DomainRepository domains) {
        this.repository = repository;
        this.users = users;
        this.tenants = tenants;
        this.domains = domains;
    }

    public Created create(String name, long userId, String createdBy,
                          Set<String> tenantCodes, Set<String> domainCodes) {
        String keyName = name == null ? "" : name.trim();
        if (keyName.isEmpty()) {
            throw new IllegalArgumentException("请给这枚 Key 起个名字");
        }
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("绑定的账号不存在：" + userId));
        if (!user.enabled()) {
            throw new IllegalArgumentException("绑定的账号已停用：" + user.username());
        }
        validateScope(tenantCodes, domainCodes);
        String rawKey = SessionToken.newToken();
        String prefix = "ka_" + rawKey.substring(0, 8);
        long id = repository.insert(keyName, prefix, SessionToken.hash(rawKey), userId, createdBy,
                tenantCodes, domainCodes);
        return new Created(rawKey, toView(repository.findById(id).orElseThrow()));
    }

    /** S5：改绑范围。绑定账号不在这里改——那是"权限即账号"的事，走账号管理。 */
    public ApiKeyView updateScope(long id, Set<String> tenantCodes, Set<String> domainCodes) {
        repository.findById(id).orElseThrow(() -> new IllegalArgumentException("API Key 不存在：" + id));
        validateScope(tenantCodes, domainCodes);
        repository.updateScope(id, tenantCodes, domainCodes);
        return toView(repository.findById(id).orElseThrow());
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
    public Optional<Resolved> resolve(String rawKey) {
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
                return Optional.of(new Resolved(user.get(), key.toScope(null)));
            }
            return Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * 绑定引用的租户/域必须真实存在：绑一个不存在的编码，等于把范围写成一个谁也到不了的地方，
     * 静默失效比报错难查得多。账号自身不在这些租户/域里不在这里拦——可见范围是"交集"，
     * 账号侧的判定照旧生效，绑宽了也越不过账号。
     */
    private void validateScope(Set<String> tenantCodes, Set<String> domainCodes) {
        for (String code : cleaned(tenantCodes)) {
            if (!tenants.exists(code)) {
                throw new IllegalArgumentException("绑定的租户不存在：" + code);
            }
        }
        for (String code : cleaned(domainCodes)) {
            if (domains.findByCode(code).isEmpty()) {
                throw new IllegalArgumentException("绑定的知识域不存在：" + code);
            }
        }
    }

    private static Set<String> cleaned(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                out.add(code.trim());
            }
        }
        return out;
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
                row.enabled(), row.createdBy(), row.createdAt(), row.lastUsedAt(),
                row.tenantCodes(), row.domainCodes());
    }
}
