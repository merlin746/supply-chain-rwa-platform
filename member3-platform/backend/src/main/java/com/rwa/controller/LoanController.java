package com.rwa.controller;

import com.rwa.auth.AuthContext;
import com.rwa.auth.AuthPrincipal;
import com.rwa.common.Result;
import com.rwa.entity.LoanApplication;
import com.rwa.service.LoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/loan")
@RequiredArgsConstructor
public class LoanController {
    private final LoanService loanService;

    @GetMapping("/list")
    public Result<List<LoanApplication>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long bankId) {
        AuthPrincipal user = AuthContext.requireCurrent();
        if ("SUPPLIER".equals(user.role())) {
            supplierId = user.enterpriseId();
            bankId = null;
        } else if ("BANK".equals(user.role())) {
            bankId = user.enterpriseId();
        } else if (!"ADMIN".equals(user.role())) {
            return Result.ok(List.of());
        }
        return Result.ok(loanService.list(status, supplierId, bankId));
    }

    @PostMapping("/apply")
    public Result<LoanApplication> apply(@RequestBody LoanApplication request) {
        try {
            AuthPrincipal user = AuthContext.requireCurrent();
            return Result.ok("融资申请已提交", loanService.apply(request, user.enterpriseId()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id) {
        return bankOperation(id, "APPROVE");
    }

    @PutMapping("/{id}/disburse")
    public Result<?> disburse(@PathVariable Long id) {
        return bankOperation(id, "DISBURSE");
    }

    @PutMapping("/{id}/settle")
    public Result<?> settle(@PathVariable Long id) {
        return bankOperation(id, "SETTLE");
    }

    @PutMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id) {
        return bankOperation(id, "REJECT");
    }

    private Result<?> bankOperation(Long id, String operation) {
        try {
            Long bankId = AuthContext.requireCurrent().enterpriseId();
            LoanApplication loan = switch (operation) {
                case "APPROVE" -> loanService.approve(id, bankId);
                case "DISBURSE" -> loanService.disburse(id, bankId);
                case "SETTLE" -> loanService.settle(id, bankId);
                case "REJECT" -> loanService.reject(id, bankId);
                default -> throw new IllegalArgumentException("不支持的融资操作");
            };
            String message = switch (operation) {
                case "APPROVE" -> "已审批，凭证进入质押状态";
                case "DISBURSE" -> "放款成功，凭证保持质押状态，到期兑付后清算";
                case "SETTLE" -> "到期兑付完成，凭证已清算";
                case "REJECT" -> "已驳回";
                default -> "操作成功";
            };
            return Result.ok(message, loan);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }
}
