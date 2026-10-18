package com.rwa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rwa.auth.AuthContext;
import com.rwa.auth.AuthPrincipal;
import com.rwa.common.Result;
import com.rwa.service.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Bearer 会话认证与粗粒度角色门禁。 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    public static final String PRINCIPAL_REQUEST_ATTRIBUTE = AuthPrincipal.class.getName();

    private static final String CORE_ENTERPRISE = "CORE_ENTERPRISE";
    private static final String SUPPLIER = "SUPPLIER";
    private static final String BANK = "BANK";
    private static final String ADMIN = "ADMIN";

    private final AuthSessionService sessionService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 池化线程复用前先清理，即使上一个请求异常中断也不会串号。
        AuthContext.clear();

        String path = matchedApplicationPath(request);
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method) || isPublic(method, path)) {
            return true;
        }

        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        Optional<AuthPrincipal> authenticated = sessionService.authenticate(token);
        if (authenticated.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "未登录或登录已过期，请重新登录");
            return false;
        }

        AuthPrincipal principal = authenticated.get();
        AuthContext.bind(principal, token);
        request.setAttribute(PRINCIPAL_REQUEST_ATTRIBUTE, principal);

        String requiredRole = requiredRole(method, path);
        if (requiredRole != null
                && !ADMIN.equals(principal.role())
                && !requiredRole.equals(principal.role())) {
            AuthContext.clear();
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "当前角色无权执行该操作");
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean isPublic(String method, String path) {
        return ("POST".equalsIgnoreCase(method) && "/auth/login".equals(path))
                || ("GET".equalsIgnoreCase(method) && "/health".equals(path))
                || ("GET".equalsIgnoreCase(method) && "/chain/status".equals(path))
                || ("GET".equalsIgnoreCase(method) && "/actuator/health".equals(path))
                || "/error".equals(path);
    }

    private String requiredRole(String method, String path) {
        if ("POST".equalsIgnoreCase(method) && "/enterprise/invoice".equals(path)) {
            return CORE_ENTERPRISE;
        }
        if ("PUT".equalsIgnoreCase(method)
                && path.matches("/enterprise/invoice/[^/]+/audit")) {
            return CORE_ENTERPRISE;
        }
        if ("POST".equalsIgnoreCase(method) && "/token/mint".equals(path)) {
            return CORE_ENTERPRISE;
        }
        if ("POST".equalsIgnoreCase(method) && "/token/split".equals(path)) {
            return SUPPLIER;
        }
        if ("POST".equalsIgnoreCase(method) && "/loan/apply".equals(path)) {
            return SUPPLIER;
        }
        if ("PUT".equalsIgnoreCase(method)
                && path.matches("/loan/[^/]+/(approve|disburse|settle|reject)")) {
            return BANK;
        }
        return null;
    }

    /**
     * 使用 Spring 已完成解码和矩阵参数处理后的匹配模板判断权限。
     * 直接比较原始 requestURI 会让诸如 /token/%6dint 或 /token/mint;foo
     * 这类仍能命中控制器的路径绕过角色门禁。
     */
    private String matchedApplicationPath(HttpServletRequest request) {
        Object matchedPattern = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matchedPattern != null) {
            return matchedPattern.toString();
        }

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    private String bearerToken(String authorization) {
        if (authorization == null || authorization.length() < 8
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(message));
    }
}
