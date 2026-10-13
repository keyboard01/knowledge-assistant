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

    public record CreateUserRequest(String username, String displayName, String roleCode, String deptCode,
                                    String password) {
    }

    public record UpdateUserRequest(String displayName, String roleCode, String deptCode, Boolean enabled) {
    }

    public record ResetPasswordRequest(String newPassword) {
    }

    private final UserAdminService service;

    public AdminUserController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public Map<String, Object> list() {
        return Map.of("users", service.list());
    }

    @GetMapping("/roles")
    public Map<String, Object> roles() {
        return Map.of("roles", service.listRoles());
    }

    @PostMapping("/users")
    public AuthService.UserView create(@RequestBody CreateUserRequest request) {
        return service.create(request.username(), request.displayName(), request.roleCode(), request.deptCode(),
                request.password());
    }

    @PutMapping("/users/{id}")
    public AuthService.UserView update(@PathVariable long id, @RequestBody UpdateUserRequest request) {
        return service.update(id, request.displayName(), request.roleCode(), request.deptCode(), request.enabled());
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
