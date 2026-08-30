package com.rwa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AuditRequest(
        @NotBlank(message = "审核动作不能为空")
        @Pattern(regexp = "APPROVE|REJECT", message = "审核动作仅支持 APPROVE / REJECT")
        String action,
        String remark
) {}
