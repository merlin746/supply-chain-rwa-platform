package com.rwa.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rwa.auth.AuthContext;
import com.rwa.auth.AuthPrincipal;
import com.rwa.common.Result;
import com.rwa.dto.LoginRequest;
import com.rwa.entity.Enterprise;
import com.rwa.entity.SysUser;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.SysUserMapper;
import com.rwa.service.AuthSessionService;
import com.rwa.service.PasswordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Demo 会话入口：登录、查看当前用户与退出。 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final SysUserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final PasswordService passwordService;
    private final AuthSessionService sessionService;

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", req.username()));
        if (user == null || !passwordService.matches(req.password(), user.getPassword())) {
            return Result.fail("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.fail("账号已停用");
        }

        Enterprise enterprise = user.getEnterpriseId() == null
                ? null : enterpriseMapper.selectById(user.getEnterpriseId());

        // 兼容历史 Demo 明文种子数据：成功登录后使用 id + 原密码做 CAS 升级。
        String storedPassword = user.getPassword();
        if (passwordService.needsUpgrade(storedPassword)) {
            LambdaUpdateWrapper<SysUser> update = new LambdaUpdateWrapper<>();
            update.eq(SysUser::getId, user.getId())
                    .eq(SysUser::getPassword, storedPassword)
                    .set(SysUser::getPassword, passwordService.encode(req.password()));
            userMapper.update(null, update);
        }

        AuthPrincipal principal = toPrincipal(user, enterprise);
        AuthSessionService.IssuedSession issued = sessionService.createSession(principal);
        Map<String, Object> data = principalData(principal);
        data.put("token", issued.token());
        data.put("expiresAt", issued.expiresAt().toString());
        data.put("sessionMode", "IN_MEMORY");
        data.put("sessionNote", "后端服务重启后需重新登录");
        return Result.ok("登录成功", data);
    }

    @GetMapping("/me")
    public Result<?> me() {
        return Result.ok(principalData(AuthContext.requireCurrent()));
    }

    @PostMapping("/logout")
    public Result<?> logout() {
        sessionService.revoke(AuthContext.requireSessionToken());
        return Result.ok("已退出登录", null);
    }

    private AuthPrincipal toPrincipal(SysUser user, Enterprise enterprise) {
        return new AuthPrincipal(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getRole(),
                user.getEnterpriseId(),
                enterprise == null ? null : enterprise.getName(),
                user.getWalletAddress());
    }

    private Map<String, Object> principalData(AuthPrincipal principal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", principal.id());
        data.put("username", principal.username());
        data.put("realName", principal.realName());
        data.put("role", principal.role());
        data.put("enterpriseId", principal.enterpriseId());
        data.put("enterpriseName", principal.enterpriseName());
        data.put("walletAddress", principal.walletAddress());
        return data;
    }
}
