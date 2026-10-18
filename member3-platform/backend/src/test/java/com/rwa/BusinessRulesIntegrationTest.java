package com.rwa;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.dto.AuditRequest;
import com.rwa.dto.MintRequest;
import com.rwa.dto.SplitRequest;
import com.rwa.entity.Enterprise;
import com.rwa.entity.LoanApplication;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.BlockchainEventMapper;
import com.rwa.mapper.LoanApplicationMapper;
import com.rwa.mapper.RwaTokenMapper;
import com.rwa.service.InvoiceService;
import com.rwa.service.LoanService;
import com.rwa.service.TokenService;
import com.rwa.service.ChainTxService;
import com.rwa.service.EventSyncService;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.core.methods.response.Log;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("demo")
@Sql(scripts = {"classpath:db/demo-schema.sql", "classpath:db/demo-data.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = {"classpath:db/demo-schema.sql", "classpath:db/demo-data.sql"},
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class BusinessRulesIntegrationTest {
    private static final String ROOT_TOKEN = "RWA-DEMO-ROOT-001";

    @Autowired LoanService loanService;
    @Autowired TokenService tokenService;
    @Autowired InvoiceService invoiceService;
    @Autowired LoanApplicationMapper loanMapper;
    @Autowired RwaTokenMapper tokenMapper;
    @Autowired EnterpriseMapper enterpriseMapper;
    @Autowired ChainTxService chainTx;
    @Autowired EventSyncService eventSync;
    @Autowired BlockchainEventMapper eventMapper;

    @Test
    void configuredChainModeCannotMintAnOffchainAssetWhenKeysAreMissing() {
        invoiceService.audit(2L, new AuditRequest("APPROVE", null), 1L);
        BigDecimal before = enterpriseMapper.selectById(1L).getCreditUsed();
        ReflectionTestUtils.setField(chainTx, "enabled", true);
        try {
            assertThatThrownBy(() -> tokenService.mint(new MintRequest(2L), 1L))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("签名密钥");
            assertThat(tokenMapper.selectCount(new QueryWrapper<RwaToken>().eq("invoice_id", 2L)))
                    .isZero();
            assertThat(enterpriseMapper.selectById(1L).getCreditUsed()).isEqualByComparingTo(before);
        } finally {
            ReflectionTestUtils.setField(chainTx, "enabled", false);
        }
    }

    @Test
    void failedEventProjectionRollsBackItsReceiptAndCanBeRetriedIdempotently() {
        Log chainLog = new Log();
        chainLog.setTransactionHash("0x" + "a".repeat(64));
        chainLog.setLogIndex("0x0");
        chainLog.setBlockNumber("0x10");
        chainLog.setAddress("0x" + "1".repeat(40));
        chainLog.setTopics(List.of(ChainTxService.TOPIC_STATUS_CHANGED,
                org.web3j.utils.Numeric.toHexStringWithPrefixZeroPadded(java.math.BigInteger.valueOf(9999), 64)));
        chainLog.setData("0x" + String.format("%064x%064x", 0, 1));

        assertThatThrownBy(() -> eventSync.processEvent(chainLog)).isInstanceOf(IllegalStateException.class);
        assertThat(eventMapper.selectCount(null)).isZero();

        RwaToken token = new RwaToken();
        token.setTokenId("9999");
        token.setEnterpriseId(2L);
        token.setInvoiceId(1L);
        token.setValueAmount(new BigDecimal("100.00"));
        token.setStatus("CIRCULATING");
        tokenMapper.insert(token);
        eventSync.processEvent(chainLog);
        eventSync.processEvent(chainLog);
        assertThat(eventMapper.selectCount(null)).isEqualTo(1);
        assertThat(tokenMapper.selectById(token.getId()).getStatus()).isEqualTo("FROZEN");

        chainLog.setTransactionHash("0x" + "b".repeat(64));
        chainLog.setData("0x" + String.format("%064x%064x", 1, 0));
        eventSync.processEvent(chainLog);
        assertThat(tokenMapper.selectById(token.getId()).getStatus()).isEqualTo("CIRCULATING");
    }

    @Test
    void fullTransferPreservesTheSourceOwnerAndTraceableLineage() {
        var transfer = tokenService.split(new SplitRequest(ROOT_TOKEN, 3L,
                new BigDecimal("2000000.00")), 2L);
        RwaToken source = (RwaToken) transfer.get("parent");
        RwaToken child = (RwaToken) transfer.get("child");
        assertThat(transfer.get("type")).isEqualTo("TRANSFER");
        assertThat(source.getEnterpriseId()).isEqualTo(2L);
        assertThat(source.getValueAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(child.getEnterpriseId()).isEqualTo(3L);
        assertThat(child.getParentTokenId()).isEqualTo(ROOT_TOKEN);
        assertThat(tokenService.verify(child.getTokenId()).get("chainDepth")).isEqualTo(2);
    }

    @Test
    void pendingLoanLocksTokenAgainstDuplicateFinancingAndSplitUntilRejected() {
        LoanApplication loan = loanService.apply(validLoan(ROOT_TOKEN), 2L);

        assertThatThrownBy(() -> loanService.apply(validLoan(ROOT_TOKEN), 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("进行中的融资申请");
        assertThatThrownBy(() -> tokenService.split(
                new SplitRequest(ROOT_TOKEN, 3L, new BigDecimal("1000.00")), 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("融资申请");

        loanService.reject(loan.getId(), 5L);
        assertThat(tokenService.split(
                new SplitRequest(ROOT_TOKEN, 3L, new BigDecimal("1000.00")), 2L).get("type"))
                .isEqualTo("SPLIT");
    }

    @Test
    void rejectsImpersonationInvalidAmountsRatesAndTerms() {
        assertThatThrownBy(() -> loanService.apply(validLoan(ROOT_TOKEN), 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前持有方");

        LoanApplication excessive = validLoan(ROOT_TOKEN);
        excessive.setAmount(new BigDecimal("2000000.01"));
        assertThatThrownBy(() -> loanService.apply(excessive, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能超过");

        LoanApplication negativeRate = validLoan(ROOT_TOKEN);
        negativeRate.setInterestRate(new BigDecimal("-0.01"));
        assertThatThrownBy(() -> loanService.apply(negativeRate, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能小于");

        LoanApplication beyondMaturity = validLoan(ROOT_TOKEN);
        beyondMaturity.setTermDays(121);
        assertThatThrownBy(() -> loanService.apply(beyondMaturity, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能超过凭证到期日");
    }

    @Test
    void settlementIsDueOnlyAtomicAndReleasesCreditExactlyOnce() {
        LoanApplication loan = loanService.apply(validLoan(ROOT_TOKEN), 2L);
        loanService.approve(loan.getId(), 5L);
        loanService.disburse(loan.getId(), 5L);

        assertThatThrownBy(() -> tokenService.split(
                new SplitRequest(ROOT_TOKEN, 3L, new BigDecimal("1000.00")), 2L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loanService.settle(loan.getId(), 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未到期");

        RwaToken token = tokenMapper.selectOne(new QueryWrapper<RwaToken>().eq("token_id", ROOT_TOKEN));
        BigDecimal settledValue = token.getValueAmount();
        token.setDueTimestamp(Instant.now().minusSeconds(1).getEpochSecond());
        tokenMapper.updateById(token);
        BigDecimal creditBefore = enterpriseMapper.selectById(1L).getCreditUsed();

        LoanApplication settled = loanService.settle(loan.getId(), 5L);
        Enterprise core = enterpriseMapper.selectById(1L);
        assertThat(settled.getStatus()).isEqualTo("SETTLED");
        assertThat(tokenMapper.selectById(token.getId()).getStatus()).isEqualTo("SETTLED");
        assertThat(core.getCreditUsed()).isEqualByComparingTo(
                creditBefore.subtract(settledValue).max(BigDecimal.ZERO));

        assertThatThrownBy(() -> loanService.settle(loan.getId(), 5L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(enterpriseMapper.selectById(1L).getCreditUsed())
                .isEqualByComparingTo(core.getCreditUsed());
    }

    @Test
    void mintIsIdempotentAndConsumesCreditOnce() {
        BigDecimal before = enterpriseMapper.selectById(1L).getCreditUsed();
        invoiceService.audit(2L, new AuditRequest("APPROVE", null), 1L);
        RwaToken token = tokenService.mint(new MintRequest(2L), 1L);

        assertThat(token.getValueAmount()).isEqualByComparingTo("800000.00");
        assertThat(enterpriseMapper.selectById(1L).getCreditUsed())
                .isEqualByComparingTo(before.add(new BigDecimal("800000.00")));
        assertThatThrownBy(() -> tokenService.mint(new MintRequest(2L), 1L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tokenMapper.selectCount(new QueryWrapper<RwaToken>().eq("invoice_id", 2L)))
                .isEqualTo(1);
    }

    @Test
    void concurrentApplicationsProduceOnlyOneActiveLoan() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        loanService.apply(validLoan(ROOT_TOKEN), 2L);
                        return true;
                    } catch (RuntimeException expected) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();
            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        assertThat(loanMapper.selectCount(new QueryWrapper<LoanApplication>()
                .eq("token_id", ROOT_TOKEN).in("status", LoanService.ACTIVE_STATUSES)))
                .isEqualTo(1);
    }

    private LoanApplication validLoan(String tokenId) {
        LoanApplication request = new LoanApplication();
        request.setTokenId(tokenId);
        request.setSupplierId(999L); // Deliberately ignored: server identity is authoritative.
        request.setBankId(5L);
        request.setAmount(new BigDecimal("100000.00"));
        request.setInterestRate(new BigDecimal("4.2000"));
        request.setTermDays(30);
        return request;
    }
}
