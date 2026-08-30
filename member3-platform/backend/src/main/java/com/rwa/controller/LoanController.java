package com.rwa.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.common.Result;
import com.rwa.entity.LoanApplication;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.LoanApplicationMapper;
import com.rwa.mapper.RwaTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/loan")
@RequiredArgsConstructor
public class LoanController {
    private final LoanApplicationMapper mapper;
    private final RwaTokenMapper tokenMapper;
    private final com.rwa.service.ChainTxService chainTx;

    @GetMapping("/list")
    public Result<List<LoanApplication>> list(@RequestParam(required=false) String status) {
        QueryWrapper<LoanApplication> q = new QueryWrapper<>();
        if (status != null && !status.isBlank()) q.eq("status", status);
        q.orderByDesc("id");
        return Result.ok(mapper.selectList(q));
    }

    @PostMapping("/apply")
    public Result<LoanApplication> apply(@RequestBody LoanApplication loan) {
        RwaToken token = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", loan.getTokenId()));
        if (token == null) return Result.fail("质押凭证不存在：" + loan.getTokenId());
        if (!List.of("UNCIRCULATED", "CIRCULATING").contains(token.getStatus())) {
            return Result.fail("凭证当前状态不允许融资：" + token.getStatus());
        }
        loan.setApplicationNo("LA-" + UUID.randomUUID().toString().substring(0,8).toUpperCase());
        loan.setStatus("APPLIED");
        loan.setCreatedAt(LocalDateTime.now());
        mapper.insert(loan);
        return Result.ok("融资申请已提交", loan);
    }

    /** 银行审批通过：凭证进入质押状态（PLEDGED） */
    @PutMapping("/{id}/approve")
    @Transactional
    public Result<?> approve(@PathVariable Long id) {
        LoanApplication loan = mapper.selectById(id);
        if (loan == null) return Result.fail("申请不存在");
        if (!"APPLIED".equals(loan.getStatus())) {
            return Result.fail("仅待审批（APPLIED）状态可审批，当前状态：" + loan.getStatus());
        }
        loan.setStatus("APPROVED");
        mapper.updateById(loan);

        RwaToken token = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", loan.getTokenId()));
        if (token != null) {
            token.setStatus("PLEDGED");
            tokenMapper.updateById(token);
        }
        return Result.ok("已审批，凭证进入质押状态", loan);
    }

    /** 银行一键放款：申请完结，凭证进入清算状态（SETTLED）。
     *  链上模式下尝试调用 settleAsset；合约要求凭证已到期，
     *  未到期会 revert，此时仅落库清算（到期清算由成员2的清算合约负责）。 */
    @PutMapping("/{id}/disburse")
    @Transactional
    public Result<?> disburse(@PathVariable Long id) {
        LoanApplication loan = mapper.selectById(id);
        if (loan == null) return Result.fail("申请不存在");
        if (!"APPROVED".equals(loan.getStatus())) {
            return Result.fail("仅已审批（APPROVED）状态可放款，当前状态：" + loan.getStatus());
        }
        loan.setStatus("DISBURSED");

        RwaToken token = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", loan.getTokenId()));

        if (chainTx.chainReady(loan.getBankId())
                && token != null && com.rwa.service.ChainTxService.isNumericTokenId(token.getTokenId())) {
            try {
                loan.setChainTxHash(chainTx.settleOnChain(loan.getBankId(), token.getTokenId()));
            } catch (Exception ignored) {
                // 凭证未到期等合约约束导致清算失败时，仅落库清算，不影响放款流程
            }
        }
        mapper.updateById(loan);

        if (token != null) {
            token.setStatus("SETTLED");
            tokenMapper.updateById(token);
        }
        return Result.ok("放款成功，凭证已清算", loan);
    }

    /** 银行驳回申请 */
    @PutMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id) {
        LoanApplication loan = mapper.selectById(id);
        if (loan == null) return Result.fail("申请不存在");
        if (!"APPLIED".equals(loan.getStatus())) {
            return Result.fail("仅待审批（APPLIED）状态可驳回，当前状态：" + loan.getStatus());
        }
        loan.setStatus("REJECTED");
        mapper.updateById(loan);
        return Result.ok("已驳回", loan);
    }
}
