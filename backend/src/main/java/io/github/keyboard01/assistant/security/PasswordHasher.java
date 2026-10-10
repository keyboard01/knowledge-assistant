package io.github.keyboard01.assistant.security;

/**
 * 口令哈希。做成接口是为了让 AuthService 的单测不必真跑 BCrypt 的慢哈希，
 * 也为了将来换算法（Argon2 等）时不动业务代码。
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);
}
