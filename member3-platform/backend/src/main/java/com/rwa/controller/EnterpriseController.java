package com.rwa.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.auth.AuthContext;
import com.rwa.auth.AuthPrincipal;
import com.rwa.common.Result;
import com.rwa.dto.AuditRequest;
import com.rwa.entity.Enterprise;
import com.rwa.entity.Invoice;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.InvoiceMapper;
import com.rwa.service.InvoiceService;
import com.rwa.service.TokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/enterprise")
@RequiredArgsConstructor
public class EnterpriseController {
    private final EnterpriseMapper enterpriseMapper;
    private final InvoiceMapper invoiceMapper;
    private final InvoiceService invoiceService;
    private final TokenService tokenService;

    @GetMapping("/list")
    public Result<List<Enterprise>> list() {
        AuthPrincipal user = AuthContext.requireCurrent();
        return Result.ok(enterpriseMapper.selectList(null).stream()
                .map(enterprise -> mayViewCredit(user, enterprise.getId())
                        ? enterprise : withoutCredit(enterprise))
                .toList());
    }

    @GetMapping("/{id}")
    public Result<Enterprise> get(@PathVariable Long id) {
        AuthPrincipal user = AuthContext.requireCurrent();
        Enterprise enterprise = enterpriseMapper.selectById(id);
        if (enterprise == null || mayViewCredit(user, id)) return Result.ok(enterprise);
        return Result.ok(withoutCredit(enterprise));
    }

    @GetMapping("/invoices")
    public Result<List<Invoice>> invoices(@RequestParam(required = false) Long supplierId) {
        AuthPrincipal user = AuthContext.requireCurrent();
        QueryWrapper<Invoice> query = new QueryWrapper<>();
        if ("CORE_ENTERPRISE".equals(user.role())) {
            query.eq("core_enterprise_id", user.enterpriseId());
            if (supplierId != null) query.eq("supplier_id", supplierId);
        } else if ("SUPPLIER".equals(user.role())) {
            query.eq("supplier_id", user.enterpriseId());
        } else if ("ADMIN".equals(user.role()) && supplierId != null) {
            query.eq("supplier_id", supplierId);
        } else if (!"ADMIN".equals(user.role())) {
            return Result.ok(List.of());
        }
        query.orderByDesc("id");
        return Result.ok(invoiceMapper.selectList(query));
    }

    @PostMapping("/invoice")
    public Result<Invoice> createInvoice(@RequestBody Invoice request) {
        try {
            Long coreEnterpriseId = AuthContext.requireCurrent().enterpriseId();
            return Result.ok("发票已创建", invoiceService.create(request, coreEnterpriseId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/invoice/{id}/audit")
    public Result<?> auditInvoice(@PathVariable Long id, @Valid @RequestBody AuditRequest request) {
        try {
            Long coreEnterpriseId = AuthContext.requireCurrent().enterpriseId();
            return Result.ok("审核完成", invoiceService.audit(id, request, coreEnterpriseId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/tokens")
    public Result<List<RwaToken>> tokens() {
        AuthPrincipal user = AuthContext.requireCurrent();
        return Result.ok(tokenService.listVisible(
                user.role(), user.enterpriseId(), null, null, null));
    }

    private boolean mayViewCredit(AuthPrincipal user, Long enterpriseId) {
        return "ADMIN".equals(user.role())
                || (user.enterpriseId() != null && user.enterpriseId().equals(enterpriseId));
    }

    private Enterprise withoutCredit(Enterprise source) {
        Enterprise directory = new Enterprise();
        directory.setId(source.getId());
        directory.setName(source.getName());
        directory.setEnterpriseCode(source.getEnterpriseCode());
        directory.setEnterpriseType(source.getEnterpriseType());
        directory.setWalletAddress(source.getWalletAddress());
        directory.setCreatedAt(source.getCreatedAt());
        directory.setUpdatedAt(source.getUpdatedAt());
        return directory;
    }
}
