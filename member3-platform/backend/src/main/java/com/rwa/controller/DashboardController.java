package com.rwa.controller;

import com.rwa.auth.AuthContext;
import com.rwa.auth.AuthPrincipal;
import com.rwa.common.Result;
import com.rwa.entity.*;
import com.rwa.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final EnterpriseMapper enterpriseMapper;
    private final RwaTokenMapper tokenMapper;
    private final LoanApplicationMapper loanMapper;
    private final InvoiceMapper invoiceMapper;

    @GetMapping("/overview")
    public Result<?> overview() {
        AuthPrincipal user = AuthContext.requireCurrent();
        List<Invoice> invoices = invoiceMapper.selectList(null);
        List<RwaToken> tokens = tokenMapper.selectList(null);
        List<LoanApplication> loans = loanMapper.selectList(null);
        if ("SUPPLIER".equals(user.role())) {
            invoices = invoices.stream().filter(i -> Objects.equals(i.getSupplierId(), user.enterpriseId())).toList();
            tokens = tokens.stream().filter(t -> Objects.equals(t.getEnterpriseId(), user.enterpriseId())).toList();
            loans = loans.stream().filter(l -> Objects.equals(l.getSupplierId(), user.enterpriseId())).toList();
        } else if ("CORE_ENTERPRISE".equals(user.role())) {
            invoices = invoices.stream().filter(i -> Objects.equals(i.getCoreEnterpriseId(), user.enterpriseId())).toList();
            Set<Long> invoiceIds = new HashSet<>();
            invoices.forEach(i -> invoiceIds.add(i.getId()));
            tokens = tokens.stream().filter(t -> invoiceIds.contains(t.getInvoiceId())).toList();
            Set<String> tokenIds = new HashSet<>();
            tokens.forEach(t -> tokenIds.add(t.getTokenId()));
            loans = loans.stream().filter(l -> tokenIds.contains(l.getTokenId())).toList();
        } else if ("BANK".equals(user.role())) {
            loans = loans.stream().filter(l -> Objects.equals(l.getBankId(), user.enterpriseId())).toList();
            Set<String> tokenIds = new HashSet<>();
            loans.forEach(l -> tokenIds.add(l.getTokenId()));
            tokens = tokens.stream().filter(t -> tokenIds.contains(t.getTokenId())).toList();
            Set<Long> invoiceIds = new HashSet<>();
            tokens.forEach(t -> invoiceIds.add(t.getInvoiceId()));
            invoices = invoices.stream().filter(i -> invoiceIds.contains(i.getId())).toList();
        }
        Map<String,Long> invoiceCounts = countStatuses(invoices.stream().map(Invoice::getStatus).toList());
        Map<String,Long> tokenCounts = countStatuses(tokens.stream().map(RwaToken::getStatus).toList());
        Map<String,Long> loanCounts = countStatuses(loans.stream().map(LoanApplication::getStatus).toList());
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("enterpriseCount", enterpriseMapper.selectCount(null));
        m.put("tokenCount", tokens.size());
        m.put("invoiceCount", invoices.size());
        m.put("loanCount", loans.size());
        m.put("assetAmount", tokens.stream().filter(t -> !List.of("SETTLED", "REVOKED").contains(t.getStatus()))
                .map(RwaToken::getValueAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("financedAmount", loans.stream().filter(l -> List.of("DISBURSED", "SETTLED").contains(l.getStatus()))
                .map(LoanApplication::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("pendingInvoices", invoiceCounts.getOrDefault("PENDING", 0L));
        m.put("pendingLoans", loanCounts.getOrDefault("APPLIED", 0L));
        m.put("approvedInvoices", invoiceCounts.getOrDefault("APPROVED", 0L));
        m.put("approvedLoans", loanCounts.getOrDefault("APPROVED", 0L));
        m.put("invoiceStatuses", invoiceCounts);
        m.put("tokenStatuses", tokenCounts);
        m.put("loanStatuses", loanCounts);
        m.put("updatedAt", java.time.Instant.now().toString());
        return Result.ok(m);
    }

    private Map<String,Long> countStatuses(List<String> statuses) {
        Map<String,Long> counts = new LinkedHashMap<>();
        statuses.stream().filter(Objects::nonNull).forEach(s -> counts.merge(s, 1L, Long::sum));
        return counts;
    }
}
