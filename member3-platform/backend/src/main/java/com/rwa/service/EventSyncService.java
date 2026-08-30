package com.rwa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.entity.BlockchainEvent;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.BlockchainEventMapper;
import com.rwa.mapper.RwaTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 链上事件同步：轮询成员1 RWA_Core_Asset 合约事件并回写业务库。
 *
 * 已对接的真实事件（topic0 由事件签名 keccak256 计算，见 ChainTxService）：
 *   AssetCreated(tokenId, issuer, slot, faceValue, maturityDate, contractHash, metadataURI)
 *   AssetSplit(parentTokenId, childTokenId, value)
 *   AssetStatusChanged(tokenId, oldStatus, newStatus)
 *   AssetSettled(tokenId, amount)
 *   AssetRevoked(tokenId, revokedBy, reason)
 *
 * 幂等设计：后端自身发起的 mint/split 已在业务事务内落库，
 * 事件同步只做「存在性检查 + 补缺」，不会重复扣减父凭证金额。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventSyncService {
    private final Web3Service web3Service;
    private final BlockchainEventMapper eventMapper;
    private final RwaTokenMapper tokenMapper;
    private final ChainTxService chainTx;
    private final com.rwa.mapper.EnterpriseMapper enterpriseMapper;

    @Value("${web3.start-block:0}")
    private long configuredStartBlock;

    @Value("${web3.enabled:true}")
    private boolean enabled;

    @Value("${web3.contract-address:}")
    private String contractAddress;

    private BigInteger lastScanned;

    @Scheduled(fixedDelayString = "${web3.event-poll-ms:10000}")
    public void sync() {
        // 未接入成员1节点前保持关闭，避免对不存在的 RPC 端点空轮询
        if (!enabled) {
            return;
        }
        try {
            if (!web3Service.contractExists()) {
                return;
            }

            BigInteger latest = web3Service.getBlockNumber();
            if (lastScanned == null) {
                lastScanned = BigInteger.valueOf(configuredStartBlock);
            }

            if (latest.compareTo(lastScanned) < 0) {
                return;
            }

            List<Log> logs = web3Service.getLogs(lastScanned, latest);
            for (Log log : logs) {
                saveEvent(log);
            }
            lastScanned = latest.add(BigInteger.ONE);
        } catch (Exception e) {
            // 不让链节点暂时不可用影响主应用；保留告警便于排查同步问题
            log.warn("链上事件同步失败：{}", e.getMessage());
        }
    }

    private void saveEvent(Log log) {
        String txHash = log.getTransactionHash();
        String topic0 = log.getTopics().isEmpty() ? "" : log.getTopics().get(0);

        Long blockNumber = log.getBlockNumber() == null
                ? null : log.getBlockNumber().longValue();

        LambdaQueryWrapper<BlockchainEvent> q = new LambdaQueryWrapper<>();
        q.eq(BlockchainEvent::getTxHash, txHash)
         .eq(BlockchainEvent::getTopic0, topic0);

        if (eventMapper.selectCount(q) > 0) {
            return;
        }

        String eventName = resolveEventName(topic0);

        String topicsJson = log.getTopics().stream()
                .map(t -> "\"" + t + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        BlockchainEvent event = new BlockchainEvent();
        event.setTxHash(txHash);
        event.setBlockNumber(blockNumber);
        event.setContractAddress(log.getAddress());
        event.setTopic0(topic0);
        event.setEventName(eventName);
        event.setRawTopics(topicsJson);
        event.setRawData(log.getData());
        event.setSyncedAt(LocalDateTime.now());
        eventMapper.insert(event);

        switch (eventName) {
            case "AssetCreated" -> onAssetCreated(log, txHash, blockNumber);
            case "AssetSplit" -> onAssetSplit(log, txHash);
            case "AssetStatusChanged" -> onStatusChanged(log);
            case "AssetSettled" -> updateTokenStatus(indexedUint(log, 1), "SETTLED", txHash);
            case "AssetRevoked" -> updateTokenStatus(indexedUint(log, 1), "REVOKED", txHash);
            default -> { }
        }
    }

    private String resolveEventName(String topic0) {
        if (ChainTxService.TOPIC_ASSET_CREATED.equalsIgnoreCase(topic0)) return "AssetCreated";
        if (ChainTxService.TOPIC_ASSET_SPLIT.equalsIgnoreCase(topic0)) return "AssetSplit";
        if (ChainTxService.TOPIC_TRANSFER_VALUE.equalsIgnoreCase(topic0)) return "TransferValue";
        if (ChainTxService.TOPIC_STATUS_CHANGED.equalsIgnoreCase(topic0)) return "AssetStatusChanged";
        if (ChainTxService.TOPIC_ASSET_REVOKED.equalsIgnoreCase(topic0)) return "AssetRevoked";
        if (ChainTxService.TOPIC_ASSET_SETTLED.equalsIgnoreCase(topic0)) return "AssetSettled";
        return "UNKNOWN";
    }

    /**
     * AssetCreated(tokenId, issuer, slot, faceValue, maturityDate, contractHash, metadataURI)
     * 后端自身发起的 mint 已落库，此处仅补缺（例如部署脚本直接签发的凭证）。
     * 事件本身不携带接收方，通过链上 ownerOf 反查持有方并映射到企业。
     */
    private void onAssetCreated(Log evLog, String txHash, Long blockNumber) {
        String tokenId = indexedUint(evLog, 1);
        if (tokenId == null || tokenExists(tokenId)) {
            return;
        }
        // data 布局：faceValue(word0), maturityDate(word1), 之后是两个 string 的偏移与内容
        BigInteger faceValueCents = dataWord(evLog, 0);
        BigInteger maturity = dataWord(evLog, 1);

        RwaToken token = new RwaToken();
        token.setTokenId(tokenId);
        token.setSlotId(indexedUint(evLog, 3));
        token.setValueAmount(faceValueCents == null ? null : ChainTxService.toYuan(faceValueCents));
        token.setDueTimestamp(maturity == null ? null : maturity.longValue());
        token.setStatus("UNCIRCULATED");
        token.setContractAddress(contractAddress);
        token.setTxHash(txHash);
        token.setBlockNumber(blockNumber);

        // 链上反查持有方 -> 映射企业（地址不区分大小写）
        try {
            String owner = chainTx.ownerOfOnChain(tokenId);
            if (owner != null) {
                token.setOwnerAddress(owner);
                com.rwa.entity.Enterprise holder = enterpriseMapper.selectOne(
                        new QueryWrapper<com.rwa.entity.Enterprise>()
                                .apply("LOWER(wallet_address) = LOWER({0})", owner));
                if (holder != null) {
                    token.setEnterpriseId(holder.getId());
                }
            }
        } catch (Exception e) {
            log.warn("反查 token {} 持有方失败：{}", tokenId, e.getMessage());
        }
        tokenMapper.insert(token);
    }

    /**
     * AssetSplit(parentTokenId, childTokenId, value)，三个参数全部 indexed。
     * 后端自身发起的 split 已在业务事务内完成父凭证扣减与子凭证入库，
     * 因此只在子凭证不存在时才补缺。
     */
    private void onAssetSplit(Log evLog, String txHash) {
        String parentTokenId = indexedUint(evLog, 1);
        String childTokenId = indexedUint(evLog, 2);
        String valueCents = indexedUint(evLog, 3);
        if (parentTokenId == null || childTokenId == null || valueCents == null) {
            return;
        }
        if (tokenExists(childTokenId)) {
            return; // 已由后端业务事务落库，跳过避免重复扣减
        }
        RwaToken parent = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", parentTokenId));
        if (parent == null) {
            return;
        }
        java.math.BigDecimal value = ChainTxService.toYuan(new BigInteger(valueCents));
        parent.setValueAmount(parent.getValueAmount().subtract(value));
        tokenMapper.updateById(parent);

        RwaToken child = new RwaToken();
        child.setTokenId(childTokenId);
        child.setSlotId(parent.getSlotId());
        child.setParentTokenId(parentTokenId);
        child.setInvoiceId(parent.getInvoiceId());
        child.setValueAmount(value);
        child.setDueTimestamp(parent.getDueTimestamp());
        child.setStatus("CIRCULATING");
        child.setContractAddress(parent.getContractAddress());
        child.setTxHash(txHash);
        child.setMetadataJson(parent.getMetadataJson());
        try {
            String owner = chainTx.ownerOfOnChain(childTokenId);
            if (owner != null) {
                child.setOwnerAddress(owner);
                com.rwa.entity.Enterprise holder = enterpriseMapper.selectOne(
                        new QueryWrapper<com.rwa.entity.Enterprise>()
                                .apply("LOWER(wallet_address) = LOWER({0})", owner));
                if (holder != null) {
                    child.setEnterpriseId(holder.getId());
                }
            }
        } catch (Exception e) {
            log.warn("反查子凭证 {} 持有方失败：{}", childTokenId, e.getMessage());
        }
        tokenMapper.insert(child);
    }

    /**
     * AssetStatusChanged(tokenId, oldStatus, newStatus)
     * tokenId indexed；old/new status 在 data 中（两个 uint8 字）。
     * 合约状态映射：0 Active（不变更业务状态）1 Frozen 2 Revoked 3 Settled。
     */
    private void onStatusChanged(Log log) {
        String tokenId = indexedUint(log, 1);
        BigInteger newStatus = dataWord(log, 1);
        if (tokenId == null || newStatus == null) {
            return;
        }
        String mapped = switch (newStatus.intValue()) {
            case 1 -> "FROZEN";
            case 2 -> "REVOKED";
            case 3 -> "SETTLED";
            default -> null; // Active 不覆盖业务状态（UNCIRCULATED/CIRCULATING/PLEDGED）
        };
        if (mapped != null) {
            updateTokenStatus(tokenId, mapped, null);
        }
    }

    private void updateTokenStatus(String tokenId, String status, String txHash) {
        if (tokenId == null) {
            return;
        }
        RwaToken token = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", tokenId));
        if (token == null) {
            return;
        }
        token.setStatus(status);
        if (txHash != null) {
            token.setTxHash(txHash);
        }
        tokenMapper.updateById(token);
    }

    private boolean tokenExists(String tokenId) {
        return tokenMapper.selectCount(
                new QueryWrapper<RwaToken>().eq("token_id", tokenId)) > 0;
    }

    /** 提取第 index 个 indexed uint256 参数（topics[index]，index 从 1 起为首个参数） */
    private String indexedUint(Log log, int index) {
        if (log.getTopics().size() <= index) {
            return null;
        }
        return new BigInteger(log.getTopics().get(index).substring(2), 16).toString();
    }

    /** 提取 data 中第 wordIndex 个 32 字节字 */
    private BigInteger dataWord(Log log, int wordIndex) {
        String data = log.getData();
        if (data == null || data.length() < 2) {
            return null;
        }
        String hex = data.substring(2);
        int start = wordIndex * 64;
        if (hex.length() < start + 64) {
            return null;
        }
        return new BigInteger(hex.substring(start, start + 64), 16);
    }
}
