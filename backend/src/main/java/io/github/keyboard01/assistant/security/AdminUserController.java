package io.github.keyboard01.assistant.security;

import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
 * 用户与角色管理（admin）。整个 /api/admin/** 由拦截器默认要求登录，此处再加角色门。
 * 独立成 /api/admin 分区，是把"管理面"与用户面接口分开的第一步。
 */
@RestController
@RequestMapping("/api/admin")
@RequireRole("admin")
public class AdminUserController {

    /**
     * 建号载荷。{@code tenantCodes} 决定这人能看哪些知识域；{@code adminTenantCodes}（其子集）
     * 决定他在哪些租户里是管理员。都留空落默认租户。
     */
    public record CreateUserRequest(String username, String displayName, String roleCode, String deptCode,
                                    String password, java.util.List<String> tenantCodes,
                                    java.util.List<String> adminTenantCodes) {
    }

    /** 改号载荷；{@code tenantCodes} 为 null 表示"不动归属"，给了就整份替换（adminTenantCodes 随之）。 */
    public record UpdateUserRequest(String displayName, String roleCode, String deptCode, Boolean enabled,
                                    java.util.List<String> tenantCodes, java.util.List<String> adminTenantCodes) {
    }

    public record ResetPasswordRequest(String newPassword) {
    }

    private final UserAdminService service;

    public AdminUserController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public Map<String, Object> list() {
        return Map.of("users", service.list(), "tenants", service.listTenants());
    }

    @GetMapping("/roles")
    public Map<String, Object> roles() {
        return Map.of("roles", service.listRoles());
    }

    @PostMapping("/users")
    public AuthService.UserView create(@RequestBody CreateUserRequest request) {
        return service.create(request.username(), request.displayName(), request.roleCode(), request.deptCode(),
                request.password(), request.tenantCodes(), request.adminTenantCodes());
    }

    @PutMapping("/users/{id}")
    public AuthService.UserView update(@PathVariable long id, @RequestBody UpdateUserRequest request) {
        return service.update(id, request.displayName(), request.roleCode(), request.deptCode(), request.enabled(),
                request.tenantCodes(), request.adminTenantCodes());
    }

    @PostMapping("/users/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id, @RequestBody ResetPasswordRequest request) {
        service.resetPassword(id, request.newPassword());
        return Map.of("ok", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage() == null ? "参数不合法" : ex.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onDuplicate(DuplicateKeyException ex) {
        return Map.of("error", "用户名已存在");
    }
}
