package com.rwa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Web3Service {

    private final Web3j web3j;

    @Value("${web3.contract-address}")
    private String contractAddress;

    /**
     * 获取区块链客户端版本
     */
    public String getClientVersion() throws Exception {
        return web3j
                .web3ClientVersion()
                .send()
                .getWeb3ClientVersion();
    }

    /**
     * 获取当前区块高度
     */
    public BigInteger getBlockNumber() throws Exception {
        EthBlockNumber response = web3j
                .ethBlockNumber()
                .send();

        return response.getBlockNumber();
    }

    /**
     * 判断智能合约是否已经部署
     */
    public boolean contractExists() throws Exception {

        if (contractAddress == null
                || contractAddress.isBlank()
                || contractAddress.equalsIgnoreCase(
                "0x0000000000000000000000000000000000000000")) {

            return false;
        }

        EthGetCode code = web3j
                .ethGetCode(
                        contractAddress,
                        DefaultBlockParameterName.LATEST
                )
                .send();

        return code.getCode() != null
                && !"0x".equals(code.getCode());
    }

    /**
     * 获取指定区块范围内的智能合约事件
     */
    public List<Log> getLogs(
            BigInteger from,
            BigInteger to
    ) throws Exception {

        // 如果目前还没有配置真实合约地址，
        // 不执行 eth_getLogs
        if (contractAddress == null
                || contractAddress.isBlank()
                || contractAddress.equalsIgnoreCase(
                "0x0000000000000000000000000000000000000000")) {

            return List.of();
        }

        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameter.valueOf(to),
                contractAddress
        );

        EthLog response = web3j
                .ethGetLogs(filter)
                .send();

        return response
                .getLogs()
                .stream()
                .map(item -> (Log) item.get())
                .toList();
    }
}