package com.rwa.service;

import com.rwa.auth.AuthPrincipal;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Demo 环境使用的内存会话存储。
 *
 * <p>Token 由 {@link SecureRandom} 生成，不包含用户信息。会话不落盘，
 * 因此后端服务重启后用户必须重新登录；这是演示版的明确边界。</p>
 */
@Service
public class AuthSessionService {
    static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    public IssuedSession createSession(AuthPrincipal principal) {
        purgeExpiredSessions();
        Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        String token;
        do {
            byte[] bytes = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (sessions.putIfAbsent(token, new Session(principal, expiresAt)) != null);

        return new IssuedSession(token, expiresAt);
    }

    public Optional<AuthPrincipal> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(Instant.now())) {
            sessions.remove(token, session);
            return Optional.empty();
        }
        return Optional.of(session.principal());
    }

    public boolean revoke(String token) {
        return token != null && sessions.remove(token) != null;
    }

    private void purgeExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    public record IssuedSession(String token, Instant expiresAt) {
    }

    private record Session(AuthPrincipal principal, Instant expiresAt) {
    }
}
