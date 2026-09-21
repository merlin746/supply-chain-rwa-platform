package com.rwa.controller;

import com.rwa.common.Result;
import com.rwa.service.Web3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {
    private final Web3Service web3Service;

    @Value("${web3.enabled:false}")
    private boolean chainEnabled;

    @GetMapping("/health")
    public Result<?> health() {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("application", "rwa-supply-chain");
        data.put("status", "UP");
        if (!chainEnabled) {
            data.put("chainStatus", "DISABLED");
            data.put("mode", "OFFCHAIN");
            return Result.ok(data);
        }
        try {
            data.put("chainClient", web3Service.getClientVersion());
            data.put("blockNumber", web3Service.getBlockNumber());
            data.put("contractExists", web3Service.contractExists());
        } catch (Exception e) {
            data.put("chainStatus", "UNAVAILABLE");
            data.put("chainError", e.getMessage());
        }
        return Result.ok(data);
    }
}
