package com.hykj.assistant.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new BCryptPasswordHasher();

    @Test
    @DisplayName("同一口令两次哈希不同（带盐），但都能验证通过")
    void hashIsSalted() {
        String first = hasher.hash("Admin@12345");
        String second = hasher.hash("Admin@12345");
        assertNotEquals(first, second);
        assertTrue(first.startsWith("$2a$"), "必须是 BCrypt 哈希，不能是明文或简单摘要");
        assertTrue(hasher.matches("Admin@12345", first));
        assertTrue(hasher.matches("Admin@12345", second));
    }

    @Test
    @DisplayName("错误口令、空结果、非 BCrypt 值一律不通过")
    void wrongPasswordNeverMatches() {
        assertFalse(hasher.matches("wrong", hasher.hash("Admin@12345")));
        assertFalse(hasher.matches("Admin@12345", null));
        assertFalse(hasher.matches("Admin@12345", ""));
        assertFalse(hasher.matches("Admin@12345", "明文密码"));
        assertFalse(hasher.matches(null, hasher.hash("Admin@12345")));
    }

    @Test
    @DisplayName("拒绝空密码，避免把空口令写进库")
    void rejectsEmptyPassword() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(""));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
    }
}
