package io.github.keyboard01.assistant.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外 API Key 管理（admin）。绑定的是**已有账号**，权限即该账号的角色/部门，所以这里不设 scope。
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@RequireRole("admin")
public class ApiKeyController {

    /** userId 是要绑定的账号；密钥绑定账号，不绑定角色。 */
    public record CreateRequest(String name, Long userId) {
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
                CurrentUser.require().username());
        return Map.of("key", created.rawKey(), "client", created.view());
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
