package com.rwa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.dto.AuditRequest;
import com.rwa.entity.Enterprise;
import com.rwa.entity.Invoice;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.InvoiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class InvoiceService {
    private final InvoiceMapper invoiceMapper;
    private final EnterpriseMapper enterpriseMapper;

    @Transactional
    public Invoice create(Invoice request, Long coreEnterpriseId) {
        if (coreEnterpriseId == null) {
            throw new IllegalArgumentException("当前账号未绑定核心企业");
        }
        Enterprise core = requireEnterprise(coreEnterpriseId, "CORE_ENTERPRISE", "核心企业");
        Enterprise supplier = requireEnterprise(request.getSupplierId(), "SUPPLIER", "供应商");
        String invoiceCode = requireText(request.getInvoiceCode(), "发票代码", 64);
        String invoiceNumber = trimToNull(request.getInvoiceNumber(), 64, "发票号码");
        BigDecimal amount = requirePositiveMoney(request.getAmount(), "发票金额");
        LocalDate issueDate = request.getIssueDate() == null ? LocalDate.now() : request.getIssueDate();
        LocalDate dueDate = request.getDueDate();
        if (dueDate == null) {
            throw new IllegalArgumentException("到期日不能为空");
        }
        if (!dueDate.isAfter(issueDate)) {
            throw new IllegalArgumentException("到期日必须晚于开票日");
        }
        if (!dueDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("到期日必须晚于今天");
        }
        if (invoiceMapper.selectCount(new QueryWrapper<Invoice>().eq("invoice_code", invoiceCode)) > 0) {
            throw new IllegalStateException("发票代码已存在：" + invoiceCode);
        }

        Invoice invoice = new Invoice();
        invoice.setInvoiceCode(invoiceCode);
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setCoreEnterpriseId(core.getId());
        invoice.setSupplierId(supplier.getId());
        invoice.setAmount(amount);
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);
        invoice.setFileHash(trimToNull(request.getFileHash(), 128, "文件哈希"));
        invoice.setStatus("PENDING");
        if (invoiceMapper.insert(invoice) != 1) {
            throw new IllegalStateException("发票创建失败");
        }
        return invoice;
    }

    @Transactional
    public Invoice audit(Long invoiceId, AuditRequest request, Long coreEnterpriseId) {
        Invoice invoice = lockInvoice(invoiceId);
        if (invoice == null) {
            throw new IllegalArgumentException("发票不存在");
        }
        if (coreEnterpriseId == null || !coreEnterpriseId.equals(invoice.getCoreEnterpriseId())) {
            throw new IllegalStateException("只能审核本核心企业的发票");
        }
        requireEnterprise(coreEnterpriseId, "CORE_ENTERPRISE", "核心企业");
        if (!"PENDING".equals(invoice.getStatus())) {
            throw new IllegalStateException("仅待审核（PENDING）状态的发票可审核，当前状态：" + invoice.getStatus());
        }
        invoice.setStatus("APPROVE".equals(request.action()) ? "APPROVED" : "REJECTED");
        if (invoiceMapper.updateById(invoice) != 1) {
            throw new IllegalStateException("发票审核状态更新失败");
        }
        return invoice;
    }

    private Invoice lockInvoice(Long id) {
        if (id == null) {
            return null;
        }
        return invoiceMapper.selectOne(new QueryWrapper<Invoice>()
                .eq("id", id)
                .last("FOR UPDATE"));
    }

    private Enterprise requireEnterprise(Long id, String expectedType, String label) {
        Enterprise enterprise = id == null ? null : enterpriseMapper.selectById(id);
        if (enterprise == null || !expectedType.equals(enterprise.getEnterpriseType())) {
            throw new IllegalArgumentException(label + "无效");
        }
        return enterprise;
    }

    private BigDecimal requirePositiveMoney(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(label + "最多保留两位小数");
        }
    }

    private String requireText(String value, String label, int maxLength) {
        String result = trimToNull(value, maxLength, label);
        if (result == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return result;
    }

    private String trimToNull(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
        return result;
    }
}
