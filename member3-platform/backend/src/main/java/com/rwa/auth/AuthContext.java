package com.rwa.auth;

import java.util.Optional;

/**
 * 当前 HTTP 请求的服务端身份上下文。
 *
 * <p>由认证拦截器绑定并在请求结束时清理。项目当前没有异步
 * Controller，因此使用非继承式 ThreadLocal 可以避免会话身份泄漏到其他请求。</p>
 */
public final class AuthContext {
    private static final ThreadLocal<Authentication> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void bind(AuthPrincipal principal, String sessionToken) {
        if (principal == null || sessionToken == null || sessionToken.isBlank()) {
            throw new IllegalArgumentException("认证上下文不完整");
        }
        CURRENT.set(new Authentication(principal, sessionToken));
    }

    public static Optional<AuthPrincipal> current() {
        Authentication authentication = CURRENT.get();
        return authentication == null
                ? Optional.empty()
                : Optional.of(authentication.principal());
    }

    public static AuthPrincipal requireCurrent() {
        return current().orElseThrow(() -> new IllegalStateException("当前请求未绑定登录用户"));
    }

    public static String requireSessionToken() {
        Authentication authentication = CURRENT.get();
        if (authentication == null) {
            throw new IllegalStateException("当前请求未绑定登录会话");
        }
        return authentication.sessionToken();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private record Authentication(AuthPrincipal principal, String sessionToken) {
    }
}
