package com.hykj.assistant.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 实现。只引 spring-security-crypto，不引整个 Spring Security：
 * 本项目的登录是自建会话，不需要它的过滤器链与 CSRF 机制。
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException ex) {
            // 库里存了不是 BCrypt 格式的东西：当作验证失败，不要把异常抛给登录接口
            return false;
        }
    }
}
