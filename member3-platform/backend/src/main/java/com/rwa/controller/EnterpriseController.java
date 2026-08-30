package com.rwa.controller;

import com.rwa.common.Result;
import com.rwa.entity.Enterprise;
import com.rwa.entity.Invoice;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.InvoiceMapper;
import com.rwa.mapper.RwaTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/enterprise")
@RequiredArgsConstructor
public class EnterpriseController {
    private final EnterpriseMapper enterpriseMapper;
    private final InvoiceMapper invoiceMapper;
    private final RwaTokenMapper tokenMapper;

    @GetMapping("/list")
    public Result<List<Enterprise>> list() {
        return Result.ok(enterpriseMapper.selectList(null));
    }

    @GetMapping("/{id}")
    public Result<Enterprise> get(@PathVariable Long id) {
        return Result.ok(enterpriseMapper.selectById(id));
    }

    @GetMapping("/invoices")
    public Result<List<Invoice>> invoices(@RequestParam(required=false) Long supplierId) {
        if (supplierId == null) return Result.ok(invoiceMapper.selectList(null));
        return Result.ok(invoiceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Invoice>()
                        .eq("supplier_id", supplierId)));
    }

    @PostMapping("/invoice")
    public Result<Invoice> createInvoice(@RequestBody Invoice invoice) {
        invoice.setStatus("PENDING");
        invoiceMapper.insert(invoice);
        return Result.ok("发票已创建", invoice);
    }

    /** 核心企业：发票审核（APPROVE / REJECT） */
    @PutMapping("/invoice/{id}/audit")
    public Result<?> auditInvoice(@PathVariable Long id,
                                  @jakarta.validation.Valid @RequestBody com.rwa.dto.AuditRequest req) {
        Invoice invoice = invoiceMapper.selectById(id);
        if (invoice == null) return Result.fail("发票不存在");
        if (!"PENDING".equals(invoice.getStatus())) {
            return Result.fail("仅待审核（PENDING）状态的发票可审核，当前状态：" + invoice.getStatus());
        }
        invoice.setStatus("APPROVE".equals(req.action()) ? "APPROVED" : "REJECTED");
        invoiceMapper.updateById(invoice);
        return Result.ok("审核完成", invoice);
    }

    @GetMapping("/tokens")
    public Result<List<RwaToken>> tokens() {
        return Result.ok(tokenMapper.selectList(null));
    }
}
