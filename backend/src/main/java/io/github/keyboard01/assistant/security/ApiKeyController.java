package io.github.keyboard01.assistant.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外 API Key 管理（admin）。绑定的是**已有账号**；动作权限仍由该账号的角色/部门决定，这里只管范围。
 *
 * <p>S5 起支持自身范围绑定（{@code tenantCodes}/{@code domainCodes}，空或不传=不限）：
 * 绑定账号可能横跨多个租户，Key 绑定把它收窄到指定范围，见 {@link KeyScope}。</p>
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@RequireRole("admin")
public class ApiKeyController {

    /** userId 是要绑定的账号；密钥绑定账号，不绑定角色。tenantCodes/domainCodes 不传=不限。 */
    public record CreateRequest(String name, Long userId, java.util.Set<String> tenantCodes,
                                java.util.Set<String> domainCodes) {
    }

    /** S5：改绑范围。空集合=不限（跟随绑定账号的可见范围）。 */
    public record ScopeRequest(java.util.Set<String> tenantCodes, java.util.Set<String> domainCodes) {
    }

    public record EnableRequest(boolean enabled) {
    }

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("keys", service.list());
    }

    /** 创建。响应里的 `key` 是明文，**只此一次**，之后只会返回前缀。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest request) {
        if (request.userId() == null) {
            throw new IllegalArgumentException("请选择要绑定的账号");
        }
        ApiKeyService.Created created = service.create(request.name(), request.userId(),
                CurrentUser.require().username(), request.tenantCodes(), request.domainCodes());
        return Map.of("key", created.rawKey(), "client", created.view());
    }

    /** S5：改绑租户/域范围。绑定账号走账号管理，不在这一起改。 */
    @PutMapping("/{id}/scope")
    public ApiKeyService.ApiKeyView updateScope(@PathVariable long id, @RequestBody ScopeRequest request) {
        return service.updateScope(id, request.tenantCodes(), request.domainCodes());
    }

    @PostMapping("/{id}/enabled")
    public ApiKeyService.ApiKeyView setEnabled(@PathVariable long id, @RequestBody EnableRequest request) {
        return service.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        service.revoke(id);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
}
