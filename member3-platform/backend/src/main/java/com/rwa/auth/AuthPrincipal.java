package com.rwa.auth;

/**
 * 由服务端会话确认的当前用户。
 *
 * <p>业务接口必须使用这里的 {@code enterpriseId}和 {@code role}，
 * 不得信任请求体中由客户端传入的企业或角色信息。</p>
 */
public record AuthPrincipal(
        Long id,
        String username,
        String realName,
        String role,
        Long enterpriseId,
        String enterpriseName,
        String walletAddress
) {
    public boolean hasRole(String expectedRole) {
        return expectedRole != null && expectedRole.equals(role);
    }
}
