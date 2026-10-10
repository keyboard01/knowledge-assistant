package io.github.keyboard01.assistant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionTokenTest {

    @Test
    @DisplayName("令牌是 256 位随机数的 URL 安全编码，且不重复")
    void tokenIsUrlSafeAndUnique() {
        String token = SessionToken.newToken();
        assertEquals(43, token.length(), "32 字节 base64url 无填充 = 43 字符");
        assertTrue(token.matches("[A-Za-z0-9_-]+"), "必须能安全放进 URL 与请求头");
        assertNotEquals(token, SessionToken.newToken());
    }

    @Test
    @DisplayName("库里存 64 位十六进制摘要，与令牌本身不同，且同一令牌摘要稳定")
    void hashIsStableHexDigest() {
        String token = SessionToken.newToken();
        String hash = SessionToken.hash(token);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, SessionToken.hash(token));
        assertNotEquals(token, hash);
        assertNotEquals(SessionToken.hash("fixed"), SessionToken.hash("fixed2"));
    }
}
