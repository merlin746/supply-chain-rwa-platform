package com.rwa.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record SplitRequest(
        @NotBlank(message = "原凭证TokenId不能为空") String fromTokenId,
        @NotNull(message = "接收方企业ID不能为空") Long toEnterpriseId,
        @NotNull(message = "拆分金额不能为空")
        @DecimalMin(value = "0.01", message = "拆分金额必须大于0") BigDecimal value
) {}
