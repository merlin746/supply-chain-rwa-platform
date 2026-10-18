package com.rwa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.entity.Enterprise;
import com.rwa.entity.LoanApplication;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.LoanApplicationMapper;
import com.rwa.mapper.RwaTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoanService {
    public static final List<String> ACTIVE_STATUSES = List.of("APPLIED", "APPROVED", "DISBURSED");
    private static final List<String> FINANCEABLE_TOKEN_STATUSES = List.of("UNCIRCULATED", "CIRCULATING");

    private final LoanApplicationMapper loanMapper;
    private final RwaTokenMapper tokenMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final ChainTxService chainTx;
    private final CreditAccountingService creditAccountingService;

    public List<LoanApplication> list(String status, Long supplierId, Long bankId) {
        QueryWrapper<LoanApplication> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status.trim());
        }
        if (supplierId != null) {
            query.eq("supplier_id", supplierId);
        }
        if (bankId != null) {
            query.eq("bank_id", bankId);
        }
        query.orderByDesc("id");
        return loanMapper.selectList(query);
    }

    @Transactional
    public LoanApplication apply(LoanApplication request, Long supplierEnterpriseId) {
        String tokenId = requireText(request.getTokenId(), "质押凭证");
        RwaToken token = lockToken(tokenId);
        if (token == null) {
            throw new IllegalArgumentException("质押凭证不存在：" + tokenId);
        }
        if (supplierEnterpriseId == null || !supplierEnterpriseId.equals(token.getEnterpriseId())) {
            throw new IllegalStateException("申请企业必须是凭证当前持有方");
        }
        requireEnterprise(supplierEnterpriseId, "SUPPLIER", "申请企业");
        Enterprise bank = requireEnterprise(request.getBankId(), "BANK", "融资机构");
        if (!FINANCEABLE_TOKEN_STATUSES.contains(token.getStatus())) {
            throw new IllegalStateException("凭证当前状态不允许融资：" + token.getStatus());
        }

        BigDecimal amount = requireMoney(request.getAmount(), "融资金额", 2, true);
        if (token.getValueAmount() == null || amount.compareTo(token.getValueAmount()) > 0) {
            throw new IllegalArgumentException("融资金额不能超过凭证余额：" + token.getValueAmount());
        }
        BigDecimal interestRate = requireMoney(request.getInterestRate(), "年化利率", 4, false);
        if (interestRate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("年化利率不能超过 100%");
        }
        Integer termDays = request.getTermDays();
        if (termDays == null || termDays <= 0 || termDays > 3650) {
            throw new IllegalArgumentException("融资期限必须为 1 到 3650 天");
        }
        validateMaturity(token, Instant.now().plus(termDays, ChronoUnit.DAYS).getEpochSecond());

        long activeLoans = loanMapper.selectCount(new QueryWrapper<LoanApplication>()
                .eq("token_id", tokenId)
                .in("status", ACTIVE_STATUSES));
        if (activeLoans > 0) {
            throw new IllegalStateException("该凭证已有进行中的融资申请");
        }

        LoanApplication loan = new LoanApplication();
        loan.setApplicationNo("LA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        loan.setTokenId(tokenId);
        loan.setSupplierId(supplierEnterpriseId);
        loan.setBankId(bank.getId());
        loan.setAmount(amount);
        loan.setInterestRate(interestRate);
        loan.setTermDays(termDays);
        loan.setRemark(trimRemark(request.getRemark()));
        loan.setStatus("APPLIED");
        loan.setCreatedAt(LocalDateTime.now());
        if (loanMapper.insert(loan) != 1) {
            throw new IllegalStateException("融资申请提交失败");
        }
        return loan;
    }

    @Transactional
    public LoanApplication approve(Long id, Long bankEnterpriseId) {
        LockedLoan locked = lockLoanAndToken(id);
        LoanApplication loan = locked.loan();
        RwaToken token = locked.token();
        requireBankOwner(loan, bankEnterpriseId);
        if (!"APPLIED".equals(loan.getStatus())) {
            throw new IllegalStateException("仅待审批（APPLIED）状态可审批，当前状态：" + loan.getStatus());
        }
        if (!FINANCEABLE_TOKEN_STATUSES.contains(token.getStatus())) {
            throw new IllegalStateException("凭证当前状态不允许质押：" + token.getStatus());
        }
        if (!loan.getSupplierId().equals(token.getEnterpriseId())) {
            throw new IllegalStateException("凭证持有方已变化，请重新发起融资申请");
        }
        if (token.getValueAmount() == null || loan.getAmount() == null
                || loan.getAmount().compareTo(token.getValueAmount()) > 0) {
            throw new IllegalStateException("融资金额已超过当前凭证余额");
        }
        validateMaturity(token, expectedLoanEnd(loan));
        verifyOnChainSnapshotWhenApplicable(token);

        token.setStatus("PLEDGED");
        if (tokenMapper.updateById(token) != 1) {
            throw new IllegalStateException("凭证质押状态更新失败");
        }
        loan.setStatus("APPROVED");
        if (loanMapper.updateById(loan) != 1) {
            throw new IllegalStateException("融资审批状态更新失败");
        }
        return loan;
    }

    @Transactional
    public LoanApplication reject(Long id, Long bankEnterpriseId) {
        LockedLoan locked = lockLoanAndToken(id);
        LoanApplication loan = locked.loan();
        requireBankOwner(loan, bankEnterpriseId);
        if (!"APPLIED".equals(loan.getStatus())) {
            throw new IllegalStateException("仅待审批（APPLIED）状态可驳回，当前状态：" + loan.getStatus());
        }
        loan.setStatus("REJECTED");
        if (loanMapper.updateById(loan) != 1) {
            throw new IllegalStateException("融资驳回状态更新失败");
        }
        return loan;
    }

    @Transactional
    public LoanApplication disburse(Long id, Long bankEnterpriseId) {
        LockedLoan locked = lockLoanAndToken(id);
        LoanApplication loan = locked.loan();
        RwaToken token = locked.token();
        requireBankOwner(loan, bankEnterpriseId);
        if (!"APPROVED".equals(loan.getStatus())) {
            throw new IllegalStateException("仅已审批（APPROVED）状态可放款，当前状态：" + loan.getStatus());
        }
        if (!"PLEDGED".equals(token.getStatus())) {
            throw new IllegalStateException("凭证未处于质押状态，不能放款");
        }
        if (!loan.getSupplierId().equals(token.getEnterpriseId())) {
            throw new IllegalStateException("凭证持有方与融资申请不一致");
        }
        validateMaturity(token, Instant.now().getEpochSecond() + 1);
        loan.setStatus("DISBURSED");
        if (loanMapper.updateById(loan) != 1) {
            throw new IllegalStateException("放款状态更新失败");
        }
        return loan;
    }

    @Transactional
    public LoanApplication settle(Long id, Long bankEnterpriseId) {
        LockedLoan locked = lockLoanAndToken(id);
        LoanApplication loan = locked.loan();
        RwaToken token = locked.token();
        requireBankOwner(loan, bankEnterpriseId);
        if (!"DISBURSED".equals(loan.getStatus())) {
            throw new IllegalStateException("仅已放款（DISBURSED）状态可执行到期兑付，当前状态：" + loan.getStatus());
        }
        if (!"PLEDGED".equals(token.getStatus())) {
            throw new IllegalStateException("质押凭证状态异常，不能执行兑付：" + token.getStatus());
        }
        if (token.getDueTimestamp() == null) {
            throw new IllegalStateException("凭证缺少到期时间，不能执行兑付");
        }

        boolean numericOnChainAsset = chainTx.isEnabled()
                && ChainTxService.isNumericTokenId(token.getTokenId());
        if (numericOnChainAsset && !chainTx.chainReady(bankEnterpriseId)) {
            throw new IllegalStateException("链上凭证清算缺少当前银行签名密钥，禁止回退链下清算");
        }
        if (numericOnChainAsset) {
            try {
                loan.setChainTxHash(chainTx.settleOnChain(bankEnterpriseId, token.getTokenId()));
            } catch (Exception e) {
                throw new IllegalStateException("链上清算失败：" + e.getMessage(), e);
            }
        } else if (token.getDueTimestamp() > Instant.now().getEpochSecond()) {
            throw new IllegalStateException("凭证未到期，到期时间戳：" + token.getDueTimestamp());
        }

        token.setStatus("SETTLED");
        if (tokenMapper.updateById(token) != 1) {
            throw new IllegalStateException("凭证清算状态更新失败");
        }
        loan.setStatus("SETTLED");
        if (loanMapper.updateById(loan) != 1) {
            throw new IllegalStateException("融资清算状态更新失败");
        }
        creditAccountingService.releaseForSettledToken(token);
        return loan;
    }

    private void verifyOnChainSnapshotWhenApplicable(RwaToken token) {
        if (!chainTx.chainReadyForRead() || !ChainTxService.isNumericTokenId(token.getTokenId())) {
            return;
        }
        try {
            java.math.BigInteger chainBalance = chainTx.balanceOfOnChain(token.getTokenId());
            String chainOwner = chainTx.ownerOfOnChain(token.getTokenId());
            boolean balanceMatches = chainBalance != null && token.getValueAmount() != null
                    && chainBalance.equals(ChainTxService.toCents(token.getValueAmount()));
            boolean ownerMatches = chainOwner != null && token.getOwnerAddress() != null
                    && chainOwner.equalsIgnoreCase(token.getOwnerAddress());
            if (!balanceMatches || !ownerMatches) {
                throw new IllegalStateException("链上余额或持有方与业务库不一致，不能审批质押");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("链上凭证核验失败，不能审批质押：" + e.getMessage(), e);
        }
    }

    private LockedLoan lockLoanAndToken(Long id) {
        LoanApplication snapshot = id == null ? null : loanMapper.selectById(id);
        if (snapshot == null) {
            throw new IllegalArgumentException("申请不存在");
        }
        RwaToken token = lockToken(snapshot.getTokenId());
        if (token == null) {
            throw new IllegalStateException("质押凭证不存在：" + snapshot.getTokenId());
        }
        LoanApplication loan = loanMapper.selectOne(new QueryWrapper<LoanApplication>()
                .eq("id", id)
                .last("FOR UPDATE"));
        if (loan == null) {
            throw new IllegalArgumentException("申请不存在");
        }
        if (!snapshot.getTokenId().equals(loan.getTokenId())) {
            throw new IllegalStateException("融资申请关联凭证已变化，请重试");
        }
        return new LockedLoan(loan, token);
    }

    private RwaToken lockToken(String tokenId) {
        return tokenMapper.selectOne(new QueryWrapper<RwaToken>()
                .eq("token_id", tokenId)
                .last("FOR UPDATE"));
    }

    private void requireBankOwner(LoanApplication loan, Long bankEnterpriseId) {
        requireEnterprise(bankEnterpriseId, "BANK", "当前银行");
        if (!bankEnterpriseId.equals(loan.getBankId())) {
            throw new IllegalStateException("只能操作本银行的融资申请");
        }
    }

    private Enterprise requireEnterprise(Long id, String type, String label) {
        Enterprise enterprise = id == null ? null : enterpriseMapper.selectById(id);
        if (enterprise == null || !type.equals(enterprise.getEnterpriseType())) {
            throw new IllegalArgumentException(label + "无效");
        }
        return enterprise;
    }

    private void validateMaturity(RwaToken token, long requiredEndEpochSecond) {
        long now = Instant.now().getEpochSecond();
        if (token.getDueTimestamp() == null || token.getDueTimestamp() <= now) {
            throw new IllegalStateException("凭证已到期或缺少到期时间，不能融资");
        }
        if (requiredEndEpochSecond > token.getDueTimestamp()) {
            throw new IllegalArgumentException("融资期限不能超过凭证到期日");
        }
    }

    private long expectedLoanEnd(LoanApplication loan) {
        if (loan.getCreatedAt() == null || loan.getTermDays() == null) {
            throw new IllegalStateException("融资申请缺少期限信息");
        }
        return loan.getCreatedAt().atZone(ZoneId.systemDefault())
                .plusDays(loan.getTermDays()).toEpochSecond();
    }

    private BigDecimal requireMoney(BigDecimal value, String label, int scale, boolean strictlyPositive) {
        if (value == null || (strictlyPositive ? value.signum() <= 0 : value.signum() < 0)) {
            throw new IllegalArgumentException(label + (strictlyPositive ? "必须大于 0" : "不能小于 0"));
        }
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(label + "最多保留 " + scale + " 位小数");
        }
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private String trimRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        String value = remark.trim();
        if (value.length() > 500) {
            throw new IllegalArgumentException("备注长度不能超过 500");
        }
        return value;
    }

    private record LockedLoan(LoanApplication loan, RwaToken token) { }
}
