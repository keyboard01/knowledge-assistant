package io.github.keyboard01.assistant.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 会话令牌：明文只在登录响应里出现一次，库里只存 SHA-256。
 * 这样即使数据库被拖走，也换不出一个可用令牌（口令用的是慢哈希，令牌用的是快哈希——
 * 因为令牌本身是 256 位随机数，没有暴力破解空间）。
 */
public final class SessionToken {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SessionToken() {
    }

    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = HEX[(digest[i] >> 4) & 0xF];
                hex[i * 2 + 1] = HEX[digest[i] & 0xF];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
