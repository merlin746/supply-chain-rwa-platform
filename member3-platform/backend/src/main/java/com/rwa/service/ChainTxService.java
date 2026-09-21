package com.rwa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.RawTransactionManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 链上交易服务：对接成员1的 RWA_Core_Asset.sol（ERC-3525）。
 *
 * 私钥按企业配置（web3.keys.<enterpriseId>），平台以托管钱包方式代各企业签名：
 *   - mintRWAAsset 需要 CORE_ENTERPRISE_ROLE（核心企业私钥）
 *   - transferFrom(fromTokenId, to, value) 需要凭证当前持有方私钥
 *   - settleAsset 需要 FINANCIAL_INSTITUTION_ROLE（银行私钥）
 *
 * 金额单位约定：链上 uint256 以「分」为单位，链下 MySQL 以「元」为单位（×100 换算）。
 */
@Service
@RequiredArgsConstructor
public class ChainTxService {

    /** 成员1合约事件签名（topic0 = keccak256(签名)） */
    public static final String SIG_ASSET_CREATED =
            "AssetCreated(uint256,address,uint256,uint256,uint256,string,string)";
    public static final String SIG_ASSET_SPLIT =
            "AssetSplit(uint256,uint256,uint256)";
    public static final String SIG_TRANSFER_VALUE =
            "TransferValue(uint256,uint256,uint256)";
    public static final String SIG_STATUS_CHANGED =
            "AssetStatusChanged(uint256,uint8,uint8)";
    public static final String SIG_ASSET_REVOKED =
            "AssetRevoked(uint256,address,string)";
    public static final String SIG_ASSET_SETTLED =
            "AssetSettled(uint256,uint256)";

    public static final String TOPIC_ASSET_CREATED = Hash.sha3String(SIG_ASSET_CREATED);
    public static final String TOPIC_ASSET_SPLIT = Hash.sha3String(SIG_ASSET_SPLIT);
    public static final String TOPIC_TRANSFER_VALUE = Hash.sha3String(SIG_TRANSFER_VALUE);
    public static final String TOPIC_STATUS_CHANGED = Hash.sha3String(SIG_STATUS_CHANGED);
    public static final String TOPIC_ASSET_REVOKED = Hash.sha3String(SIG_ASSET_REVOKED);
    public static final String TOPIC_ASSET_SETTLED = Hash.sha3String(SIG_ASSET_SETTLED);

    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(3_000_000);
    private static final BigDecimal CENTS_PER_YUAN = new BigDecimal(100);

    private final Web3j web3j;
    private final com.rwa.config.Web3Properties web3Properties;

    @Value("${web3.enabled:false}")
    private boolean enabled;

    @Value("${web3.contract-address:}")
    private String contractAddress;

    /** 链上交易失败时是否回退链下模式（演示容错，生产建议 false） */
    @Value("${web3.tx-fallback-offchain:true}")
    private boolean fallbackOffchain;

    private Map<Long, String> enterpriseKeys() {
        return web3Properties.getKeys();
    }

    public boolean isFallbackOffchain() {
        return fallbackOffchain;
    }

    /** 链上模式是否就绪：开关打开 + 合约地址已配置 + 该企业已配置私钥 */
    public boolean chainReady(Long enterpriseId) {
        return chainReadyForRead()
                && enterpriseId != null && enterpriseKeys().containsKey(enterpriseId);
    }

    /** 只读链上查询是否就绪（不需要私钥） */
    public boolean chainReadyForRead() {
        return enabled
                && contractAddress != null && !contractAddress.isBlank()
                && !contractAddress.equalsIgnoreCase("0x0000000000000000000000000000000000000000");
    }

    /** 元 -> 链上分 */
    public static BigInteger toCents(BigDecimal yuan) {
        return yuan.multiply(CENTS_PER_YUAN).toBigInteger();
    }

    /** 链上分 -> 元 */
    public static BigDecimal toYuan(BigInteger cents) {
        return new BigDecimal(cents).divide(CENTS_PER_YUAN);
    }

    public static boolean isNumericTokenId(String tokenId) {
        return tokenId != null && tokenId.matches("\\d+");
    }

    // ═══════════════ 写操作（发交易） ═══════════════

    /**
     * 核心企业签发凭证：mintRWAAsset(to, slot, value, maturityDate, contractHash, uri)
     * @return [tokenId, txHash]
     */
    public String[] mintOnChain(Long coreEnterpriseId, String toAddress, BigInteger slot,
                                BigInteger valueCents, long maturityDate,
                                String contractHash, String uri) throws Exception {
        Function function = new Function(
                "mintRWAAsset",
                List.of(new Address(toAddress), new Uint256(slot), new Uint256(valueCents),
                        new Uint256(BigInteger.valueOf(maturityDate)),
                        new Utf8String(contractHash), new Utf8String(uri)),
                List.of(new TypeReference<Uint256>() {}));

        TransactionReceipt receipt = send(enterpriseKeys().get(coreEnterpriseId), function);
        String tokenId = extractIndexedUint(receipt, TOPIC_ASSET_CREATED, 1)
                .orElseThrow(() -> new IllegalStateException("交易成功但未找到 AssetCreated 事件"));
        return new String[]{tokenId, receipt.getTransactionHash()};
    }

    /**
     * 凭证拆分：transferFrom(fromTokenId, to, value)
     * 合约会为接收方铸造继承 Slot/到期日的子凭证并触发 AssetSplit 事件。
     * @return [childTokenId, txHash]
     */
    public String[] splitOnChain(Long holderEnterpriseId, String fromTokenId, String toAddress,
                                 BigInteger valueCents) throws Exception {
        Function function = new Function(
                "transferFrom",
                List.of(new Uint256(new BigInteger(fromTokenId)), new Address(toAddress),
                        new Uint256(valueCents)),
                List.of(new TypeReference<Uint256>() {}));

        TransactionReceipt receipt = send(enterpriseKeys().get(holderEnterpriseId), function);
        String childTokenId = extractIndexedUint(receipt, TOPIC_ASSET_SPLIT, 2)
                .orElseThrow(() -> new IllegalStateException("交易成功但未找到 AssetSplit 事件"));
        return new String[]{childTokenId, receipt.getTransactionHash()};
    }

    /** 银行到期清算：settleAsset(tokenId)，要求凭证已到期（block.timestamp >= maturityDate） */
    public String settleOnChain(Long bankEnterpriseId, String tokenId) throws Exception {
        Function function = new Function(
                "settleAsset",
                List.of(new Uint256(new BigInteger(tokenId))),
                List.of());
        TransactionReceipt receipt = send(enterpriseKeys().get(bankEnterpriseId), function);
        return receipt.getTransactionHash();
    }

    /** 调用合约 computeSlot(issuer, maturityDate, category)，与成员1部署脚本保持一致 */
    public BigInteger computeSlot(String issuerAddress, long maturityDate, long category) {
        try {
            Function function = new Function(
                    "computeSlot",
                    List.of(new Address(issuerAddress),
                            new Uint256(BigInteger.valueOf(maturityDate)),
                            new Uint256(BigInteger.valueOf(category))),
                    List.of(new TypeReference<Uint256>() {}));
            List<Type> result = callView(function);
            if (!result.isEmpty()) {
                return (BigInteger) result.get(0).getValue();
            }
        } catch (Exception ignored) {
        }
        // 兜底：直接用到期时间戳作为 Slot（同一到期日的凭证归入同一 Slot）
        return BigInteger.valueOf(maturityDate);
    }

    // ═══════════════ 读操作（eth_call） ═══════════════

    /** 链上余额（分）：balanceOf(tokenId) */
    public BigInteger balanceOfOnChain(String tokenId) throws Exception {
        Function function = new Function(
                "balanceOf",
                List.of(new Uint256(new BigInteger(tokenId))),
                List.of(new TypeReference<Uint256>() {}));
        List<Type> result = callView(function);
        return result.isEmpty() ? null : (BigInteger) result.get(0).getValue();
    }

    /** 链上持有方：ownerOf(tokenId) */
    public String ownerOfOnChain(String tokenId) throws Exception {
        Function function = new Function(
                "ownerOf",
                List.of(new Uint256(new BigInteger(tokenId))),
                List.of(new TypeReference<Address>() {}));
        List<Type> result = callView(function);
        return result.isEmpty() ? null : result.get(0).getValue().toString();
    }

    // ═══════════════ 内部工具 ═══════════════

    private List<Type> callView(Function function) throws Exception {
        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                        Address.DEFAULT.getValue(), contractAddress,
                        FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        if (response.hasError()) {
            throw new IllegalStateException("eth_call 失败：" + response.getError().getMessage());
        }
        return FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
    }

    private TransactionReceipt send(String privateKey, Function function) throws Exception {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("未配置该企业私钥（web3.keys）");
        }
        Credentials credentials = Credentials.create(privateKey);
        long chainId = web3j.ethChainId().send().getChainId().longValue();
        RawTransactionManager txManager = new RawTransactionManager(web3j, credentials, chainId);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();

        EthSendTransaction sent = txManager.sendTransaction(
                gasPrice, GAS_LIMIT, contractAddress,
                FunctionEncoder.encode(function), BigInteger.ZERO);
        if (sent.hasError()) {
            throw new IllegalStateException("交易发送失败：" + sent.getError().getMessage());
        }

        String txHash = sent.getTransactionHash();
        for (int i = 0; i < 60; i++) {
            EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(txHash).send();
            Optional<TransactionReceipt> receipt = receiptResp.getTransactionReceipt();
            if (receipt.isPresent()) {
                TransactionReceipt r = receipt.get();
                if (!r.isStatusOK()) {
                    throw new IllegalStateException("链上交易执行失败（revert），txHash=" + txHash);
                }
                return r;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("等待交易回执超时，txHash=" + txHash);
    }

    /** 从回执日志中提取指定事件的第 index 个 indexed uint256 参数（topics[index]） */
    private Optional<String> extractIndexedUint(TransactionReceipt receipt, String topic0, int index) {
        for (Log log : receipt.getLogs()) {
            if (log.getTopics().size() > index && topic0.equalsIgnoreCase(log.getTopics().get(0))) {
                return Optional.of(new BigInteger(log.getTopics().get(index).substring(2), 16).toString());
            }
        }
        return Optional.empty();
    }
}
