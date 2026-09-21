package com.rwa.dto;

import jakarta.validation.constraints.NotNull;

public record MintRequest(
        @NotNull(message = "发票ID不能为空") Long invoiceId
) {}
