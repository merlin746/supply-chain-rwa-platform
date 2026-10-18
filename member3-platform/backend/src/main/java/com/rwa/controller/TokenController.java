package com.rwa.controller;

import com.rwa.auth.AuthContext;
import com.rwa.auth.AuthPrincipal;
import com.rwa.common.Result;
import com.rwa.dto.MintRequest;
import com.rwa.dto.SplitRequest;
import com.rwa.entity.RwaToken;
import com.rwa.service.TokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/token")
@RequiredArgsConstructor
public class TokenController {
    private final TokenService tokenService;

    @GetMapping("/list")
    public Result<List<RwaToken>> list(
            @RequestParam(required = false) String ownerAddress,
            @RequestParam(required = false) Long enterpriseId,
            @RequestParam(required = false) String status) {
        AuthPrincipal user = AuthContext.requireCurrent();
        return Result.ok(tokenService.listVisible(
                user.role(), user.enterpriseId(), ownerAddress, enterpriseId, status));
    }

    @GetMapping("/{tokenId}")
    public Result<RwaToken> detail(@PathVariable String tokenId) {
        try {
            AuthPrincipal user = AuthContext.requireCurrent();
            RwaToken token = tokenService.getVisible(tokenId, user.role(), user.enterpriseId());
            return token == null ? Result.fail("凭证不存在：" + tokenId) : Result.ok(token);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/mint")
    public Result<?> mint(@Valid @RequestBody MintRequest request) {
        try {
            Long coreEnterpriseId = AuthContext.requireCurrent().enterpriseId();
            return Result.ok("凭证开立成功", tokenService.mint(request, coreEnterpriseId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/split")
    public Result<?> split(@Valid @RequestBody SplitRequest request) {
        try {
            Long holderEnterpriseId = AuthContext.requireCurrent().enterpriseId();
            Map<String, Object> result = tokenService.split(request, holderEnterpriseId);
            String message = "TRANSFER".equals(result.get("type"))
                    ? "整体转让成功" : "拆分成功，金额守恒已校验";
            return Result.ok(message, result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/topology")
    public Result<?> topology() {
        AuthPrincipal user = AuthContext.requireCurrent();
        return Result.ok(tokenService.topology(user.role(), user.enterpriseId()));
    }

    @GetMapping("/{tokenId}/verify")
    public Result<?> verify(@PathVariable String tokenId) {
        try {
            AuthPrincipal user = AuthContext.requireCurrent();
            RwaToken token = tokenService.getVisible(tokenId, user.role(), user.enterpriseId());
            if (token == null) return Result.fail("凭证不存在：" + tokenId);
            return Result.ok(tokenService.verify(tokenId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }
}
