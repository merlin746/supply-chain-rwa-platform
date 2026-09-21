package com.rwa.controller;

import com.rwa.common.Result;
import com.rwa.service.Web3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/chain")
@RequiredArgsConstructor
public class ChainController {
    private final Web3Service web3Service;

    @org.springframework.beans.factory.annotation.Value("${web3.enabled:true}")
    private boolean chainEnabled;

    @GetMapping("/status")
    public Result<?> status() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("mode", chainEnabled ? "ONCHAIN" : "OFFCHAIN");
        if (!chainEnabled) {
            m.put("clientVersion", "链下模式（未接入成员1节点）");
            m.put("contractExists", false);
            m.put("verified", true);
            return Result.ok(m);
        }
        try {
            m.put("clientVersion", web3Service.getClientVersion());
            m.put("blockNumber", web3Service.getBlockNumber());
            m.put("contractExists", web3Service.contractExists());
            m.put("verified", true);
            return Result.ok(m);
        } catch (Exception e) {
            m.put("verified", false);
            m.put("error", e.getMessage());
            return Result.ok(m);
        }
    }
}
