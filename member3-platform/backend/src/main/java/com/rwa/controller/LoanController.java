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

    /** 银行一键放款：资金划转给供应商，凭证保持质押状态（PLEDGED）。
     *  到期兑付（SETTLED）由 /loan/{id}/settle 在凭证到期后执行，
     *  与链上状态机（Active -> Settled 需 block.timestamp >= maturityDate）保持一致。 */
    @PutMapping("/{id}/disburse")
    @Transactional
    public Result<?> disburse(@PathVariable Long id) {
        LoanApplication loan = mapper.selectById(id);
        if (loan == null) return Result.fail("申请不存在");
        if (!"APPROVED".equals(loan.getStatus())) {
            return Result.fail("仅已审批（APPROVED）状态可放款，当前状态：" + loan.getStatus());
        }
        loan.setStatus("DISBURSED");
        mapper.updateById(loan);
        return Result.ok("放款成功，凭证保持质押状态，到期兑付后清算", loan);
    }

    /** 到期兑付：凭证到期后由银行执行清算，链上调用 settleAsset，凭证置为 SETTLED。
     *  链上模式下不做回退——兑付是资金终态操作，失败必须显式暴露。 */
    @PutMapping("/{id}/settle")
    @Transactional
    public Result<?> settle(@PathVariable Long id) {
        LoanApplication loan = mapper.selectById(id);
        if (loan == null) return Result.fail("申请不存在");
        if (!"DISBURSED".equals(loan.getStatus())) {
            return Result.fail("仅已放款（DISBURSED）状态可执行到期兑付，当前状态：" + loan.getStatus());
        }
        RwaToken token = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", loan.getTokenId()));
        if (token == null) return Result.fail("质押凭证不存在：" + loan.getTokenId());

        boolean onChain = chainTx.chainReady(loan.getBankId())
                && com.rwa.service.ChainTxService.isNumericTokenId(token.getTokenId());

        if (onChain) {
            // 链上模式：到期判断交给合约（require block.timestamp >= maturityDate），
            // 后端不另用系统时钟校验，避免链上时间（如演示环境时间快进）与系统时间不一致
            try {
                loan.setChainTxHash(chainTx.settleOnChain(loan.getBankId(), token.getTokenId()));
            } catch (Exception e) {
                return Result.fail("链上清算失败：" + e.getMessage());
            }
        } else {
            // 链下模式：合约约束不可用，由后端按系统时间兜底校验
            if (token.getDueTimestamp() != null
                    && token.getDueTimestamp() > System.currentTimeMillis() / 1000) {
                return Result.fail("凭证未到期，到期时间戳：" + token.getDueTimestamp());
            }
        }

        token.setStatus("SETTLED");
        tokenMapper.updateById(token);
        loan.setStatus("SETTLED");
        mapper.updateById(loan);
        return Result.ok("到期兑付完成，凭证已清算", loan);
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
