package com.rwa.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.common.Result;
import com.rwa.dto.MintRequest;
import com.rwa.dto.SplitRequest;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.RwaTokenMapper;
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
    private final RwaTokenMapper mapper;
    private final TokenService tokenService;

    @GetMapping("/list")
    public Result<List<RwaToken>> list(
            @RequestParam(required=false) String ownerAddress,
            @RequestParam(required=false) Long enterpriseId,
            @RequestParam(required=false) String status) {
        QueryWrapper<RwaToken> q = new QueryWrapper<>();
        if (ownerAddress != null && !ownerAddress.isBlank()) q.eq("owner_address", ownerAddress);
        if (enterpriseId != null) q.eq("enterprise_id", enterpriseId);
        if (status != null && !status.isBlank()) q.eq("status", status);
        q.orderByDesc("id");
        return Result.ok(mapper.selectList(q));
    }

    @GetMapping("/{tokenId}")
    public Result<RwaToken> detail(@PathVariable String tokenId) {
        return Result.ok(mapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", tokenId)));
    }

    /** 核心企业：基于已审核发票开立凭证（链下模式，对接成员1后追加真实 mint） */
    @PostMapping("/mint")
    public Result<?> mint(@Valid @RequestBody MintRequest req) {
        try {
            return Result.ok("凭证开立成功", tokenService.mint(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 供应商：凭证无损拆分/整体转让（链下模式，对接成员2后追加真实 transferFromValue） */
    @PostMapping("/split")
    public Result<?> split(@Valid @RequestBody SplitRequest req) {
        try {
            Map<String, Object> result = tokenService.split(req);
            String msg = "TRANSFER".equals(result.get("type")) ? "整体转让成功" : "拆分成功，金额守恒已校验";
            return Result.ok(msg, result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 信用穿透拓扑（数据驱动，供 ECharts 图谱使用） */
    @GetMapping("/topology")
    public Result<?> topology() {
        return Result.ok(tokenService.topology());
    }

    /** 银行端：凭证溯源真实性核验（回溯至根凭证 + 关联发票/融资记录） */
    @GetMapping("/{tokenId}/verify")
    public Result<?> verify(@PathVariable String tokenId) {
        try {
            return Result.ok(tokenService.verify(tokenId));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }
}
