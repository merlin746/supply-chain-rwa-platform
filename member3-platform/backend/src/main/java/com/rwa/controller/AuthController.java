package com.rwa.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.common.Result;
import com.rwa.dto.LoginRequest;
import com.rwa.entity.Enterprise;
import com.rwa.entity.SysUser;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.SysUserMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演示级登录：明文比对（比赛 Demo 用，生产需替换为 BCrypt + JWT）。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final SysUserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", req.username()));
        if (user == null || !user.getPassword().equals(req.password())) {
            return Result.fail("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.fail("账号已停用");
        }

        Enterprise enterprise = user.getEnterpriseId() == null
                ? null : enterpriseMapper.selectById(user.getEnterpriseId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("realName", user.getRealName());
        data.put("role", user.getRole());
        data.put("enterpriseId", user.getEnterpriseId());
        data.put("enterpriseName", enterprise == null ? null : enterprise.getName());
        data.put("walletAddress", user.getWalletAddress());
        return Result.ok("登录成功", data);
    }
}
