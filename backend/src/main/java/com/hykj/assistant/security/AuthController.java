package com.hykj.assistant.security;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 接收前端表单。密码只在这一次请求体里出现，不写日志、不回显。 */
    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, AuthService.UserView user) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        return new LoginResponse(result.token(), result.expiresAt(), result.user());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        authService.logout(AuthInterceptor.tokenOf(request));
        return Map.of("ok", true);
    }

    /** 前端用它判断"我登录了吗"，401 即未登录。 */
    @GetMapping("/me")
    public Map<String, Object> me() {
        return Map.of("user", AuthService.UserView.of(CurrentUser.require()));
    }

    /** 自助改密：旧密码不对返回 400（不是 401，否则前端会当成会话失效把人踢回登录页）。 */
    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest body,
                                              HttpServletRequest request) {
        authService.changePassword(CurrentUser.require().username(), AuthInterceptor.tokenOf(request),
                body.oldPassword(), body.newPassword());
        return Map.of("ok", true);
    }

    @GetMapping("/users")
    @RequireRole("admin")
    public Map<String, Object> users() {
        return Map.of("users", authService.listUsers());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> onInvalidCredentials(InvalidCredentialsException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage() == null ? "参数不合法" : ex.getMessage());
    }
}
