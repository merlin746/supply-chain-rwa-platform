package com.rwa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.entity.Enterprise;
import com.rwa.entity.Invoice;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.InvoiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class CreditAccountingService {
    private final InvoiceMapper invoiceMapper;
    private final EnterpriseMapper enterpriseMapper;

    /** Caller must already hold the token row lock and execute inside a transaction. */
    public void releaseForSettledToken(RwaToken token) {
        if (token.getInvoiceId() == null || token.getValueAmount() == null
                || token.getValueAmount().signum() <= 0) {
            return;
        }
        Invoice invoice = invoiceMapper.selectById(token.getInvoiceId());
        if (invoice == null || invoice.getCoreEnterpriseId() == null) {
            throw new IllegalStateException("凭证缺少可核验的底层发票，无法归还授信额度");
        }
        Enterprise core = enterpriseMapper.selectOne(new QueryWrapper<Enterprise>()
                .eq("id", invoice.getCoreEnterpriseId())
                .last("FOR UPDATE"));
        if (core == null || !"CORE_ENTERPRISE".equals(core.getEnterpriseType())) {
            throw new IllegalStateException("凭证关联的核心企业无效");
        }
        BigDecimal used = core.getCreditUsed() == null ? BigDecimal.ZERO : core.getCreditUsed();
        core.setCreditUsed(used.subtract(token.getValueAmount()).max(BigDecimal.ZERO));
        if (enterpriseMapper.updateById(core) != 1) {
            throw new IllegalStateException("核心企业授信额度归还失败");
        }
    }
}
