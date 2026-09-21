package com.rwa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "web3")
public class Web3Properties {
    /** 企业ID -> 托管私钥（本地演示为 Hardhat 公开账户） */
    private Map<Long, String> keys = new HashMap<>();
}
