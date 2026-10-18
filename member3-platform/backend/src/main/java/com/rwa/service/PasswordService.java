package com.rwa.service;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 零新增依赖的密码校验与升级服务。
 *
 * <p>历史 Demo 种子数据中的明文密码仍可登录，首次成功登录后会被升级为
 * 带随机 salt 的 PBKDF2-HMAC-SHA256 格式。新格式为：
 * {@code pbkdf2$iterations$salt$derivedKey}。</p>
 */
@Service
public class PasswordService {
    private static final String PREFIX = "pbkdf2$";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int MAX_ACCEPTED_ITERATIONS = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();

    public boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) {
            return false;
        }
        if (!storedPassword.startsWith(PREFIX)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    storedPassword.getBytes(StandardCharsets.UTF_8));
        }

        String[] parts = storedPassword.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 10_000 || iterations > MAX_ACCEPTED_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < SALT_BYTES || expected.length != HASH_BYTES) {
                return false;
            }
            byte[] actual = derive(rawPassword, salt, iterations, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    public String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        try {
            byte[] hash = derive(rawPassword, salt, ITERATIONS, HASH_BYTES);
            return PREFIX + ITERATIONS + "$"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JDK 不支持 PBKDF2WithHmacSHA256", e);
        }
    }

    public boolean needsUpgrade(String storedPassword) {
        return storedPassword != null && !storedPassword.startsWith(PREFIX);
    }

    private byte[] derive(String rawPassword, byte[] salt, int iterations, int hashBytes)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, hashBytes * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
