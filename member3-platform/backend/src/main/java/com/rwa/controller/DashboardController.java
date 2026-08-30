package com.rwa.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.common.Result;
import com.rwa.entity.*;
import com.rwa.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

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
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("enterpriseCount", enterpriseMapper.selectCount(null));
        m.put("tokenCount", tokenMapper.selectCount(null));
        m.put("invoiceCount", invoiceMapper.selectCount(null));
        m.put("loanCount", loanMapper.selectCount(null));
        m.put("topology", List.of(
            Map.of("source","比亚迪","target","科达利","value",1),
            Map.of("source","科达利","target","聚能永拓","value",1),
            Map.of("source","聚能永拓","target","长园特发","value",1)
        ));
        return Result.ok(m);
    }
}
