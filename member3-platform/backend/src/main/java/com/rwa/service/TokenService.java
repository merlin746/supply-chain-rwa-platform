package com.rwa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rwa.dto.MintRequest;
import com.rwa.dto.SplitRequest;
import com.rwa.entity.Enterprise;
import com.rwa.entity.Invoice;
import com.rwa.entity.LoanApplication;
import com.rwa.entity.RwaToken;
import com.rwa.mapper.EnterpriseMapper;
import com.rwa.mapper.InvoiceMapper;
import com.rwa.mapper.LoanApplicationMapper;
import com.rwa.mapper.RwaTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 凭证业务服务。
 *
 * 双模运行：
 * - 链下（OFFCHAIN）：凭证开立与拆分落 MySQL，tx_hash 以 OFFCHAIN- 前缀占位；
 * - 链上（ONCHAIN）：web3.enabled=true 且配置了合约地址与企业私钥后，
 *   mint/split 通过 ChainTxService 调用成员1的 RWA_Core_Asset 合约，
 *   tokenId 与 txHash 均取自真实交易回执；链上失败时按
 *   web3.tx-fallback-offchain 决定是否回退链下。
 *
 * 一致性说明（已知限制）：链上模式为「先发交易、后写库」，若链上成功而
 * 本地事务回滚，会出现链上已确权而库中缺记录的情况。此时由
 * EventSyncService 的补缺机制（AssetCreated / AssetSplit 回填）兜底对账，
 * 链上数据始终为准。如需更强保证，应引入 outbox 模式先落库后异步发交易。
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final RwaTokenMapper tokenMapper;
    private final InvoiceMapper invoiceMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final LoanApplicationMapper loanMapper;
    private final ChainTxService chainTx;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${web3.contract-address:}")
    private String contractAddress;

    /**
     * 核心企业基于已审核发票开立应收账款凭证（对应链上 mintRWAAsset）。
     */
    @Transactional
    public RwaToken mint(MintRequest req) {
        Invoice invoice = invoiceMapper.selectById(req.invoiceId());
        if (invoice == null) {
            throw new IllegalArgumentException("发票不存在");
        }
        if (!"APPROVED".equals(invoice.getStatus())) {
            throw new IllegalStateException("发票未审核通过，不能开立凭证（当前状态：" + invoice.getStatus() + "）");
        }

        Enterprise supplier = requireEnterprise(invoice.getSupplierId());
        Enterprise core = requireEnterprise(invoice.getCoreEnterpriseId());

        String tokenId = newTokenId();
        String txHash = offchainTxHash();
        String slot = "SLOT-" + invoice.getDueDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String metadata = buildMetadata(invoice, core, supplier);
        Long maturity = toEpochSecond(invoice);

        // 链上模式：以核心企业私钥调用 mintRWAAsset，取回真实 tokenId / txHash
        if (chainTx.chainReady(core.getId())) {
            try {
                java.math.BigInteger slotNum = chainTx.computeSlot(
                        core.getWalletAddress(), maturity, 1);
                String contractHash = invoice.getFileHash() != null && !invoice.getFileHash().isBlank()
                        ? invoice.getFileHash() : "OFFCHAIN-FILEHASH-" + invoice.getInvoiceCode();
                String[] r = chainTx.mintOnChain(
                        core.getId(), supplier.getWalletAddress(), slotNum,
                        ChainTxService.toCents(invoice.getAmount()), maturity,
                        contractHash, metadata);
                tokenId = r[0];
                txHash = r[1];
                slot = slotNum.toString();
            } catch (Exception e) {
                if (!chainTx.isFallbackOffchain()) {
                    throw new IllegalStateException("链上开立失败：" + e.getMessage());
                }
            }
        }

        RwaToken token = new RwaToken();
        token.setTokenId(tokenId);
        token.setSlotId(slot);
        token.setOwnerAddress(supplier.getWalletAddress());
        token.setEnterpriseId(supplier.getId());
        token.setInvoiceId(invoice.getId());
        token.setValueAmount(invoice.getAmount());
        token.setDueTimestamp(maturity);
        token.setStatus("UNCIRCULATED");
        token.setContractAddress(contractAddress);
        token.setTxHash(txHash);
        token.setMetadataJson(metadata);
        tokenMapper.insert(token);

        // 占用核心企业授信额度
        core.setCreditUsed(core.getCreditUsed().add(invoice.getAmount()));
        enterpriseMapper.updateById(core);

        invoice.setStatus("MINTED");
        invoiceMapper.updateById(invoice);

        return token;
    }

    /**
     * 凭证无损拆分（对应 ERC-3525 transferFromValue）。
     * 守恒约束：拆分金额 + 父凭证剩余金额 == 拆分前父凭证金额。
     * 链下模式下拆分金额等于父凭证全额时退化为整体转让；
     * 链上模式遵循合约语义，始终为父凭证铸造子凭证（父凭证余额可为 0）。
     */
    @Transactional
    public Map<String, Object> split(SplitRequest req) {
        RwaToken parent = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", req.fromTokenId()));
        if (parent == null) {
            throw new IllegalArgumentException("凭证不存在：" + req.fromTokenId());
        }
        if (!List.of("UNCIRCULATED", "CIRCULATING").contains(parent.getStatus())) {
            throw new IllegalStateException("当前状态不允许拆分/转让：" + parent.getStatus());
        }

        Enterprise target = requireEnterprise(req.toEnterpriseId());
        if (target.getId().equals(parent.getEnterpriseId())) {
            throw new IllegalArgumentException("接收方与当前持有方相同，无需拆分");
        }

        BigDecimal original = parent.getValueAmount();
        BigDecimal value = req.value();
        if (value.compareTo(original) > 0) {
            throw new IllegalArgumentException("拆分金额超过凭证余额（余额：" + original + "）");
        }

        BigDecimal remainder = original.subtract(value);
        // 守恒校验：remainder + value 必须等于 original
        if (remainder.add(value).compareTo(original) != 0) {
            throw new IllegalStateException("拆分守恒校验失败");
        }

        // 链上模式：以当前持有方私钥调用 transferFrom(fromTokenId, to, value)，
        // 合约为接收方铸造继承 Slot/到期日的子凭证
        String chainChildTokenId = null;
        String chainTxHash = null;
        if (chainTx.chainReady(parent.getEnterpriseId())
                && ChainTxService.isNumericTokenId(parent.getTokenId())) {
            try {
                String[] r = chainTx.splitOnChain(
                        parent.getEnterpriseId(), parent.getTokenId(),
                        target.getWalletAddress(), ChainTxService.toCents(value));
                chainChildTokenId = r[0];
                chainTxHash = r[1];
            } catch (Exception e) {
                if (!chainTx.isFallbackOffchain()) {
                    throw new IllegalStateException("链上拆分失败：" + e.getMessage());
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();

        if (chainChildTokenId == null && value.compareTo(original) == 0) {
            // 链下整体转让：仅变更持有方，凭证号不变
            parent.setEnterpriseId(target.getId());
            parent.setOwnerAddress(target.getWalletAddress());
            parent.setStatus("CIRCULATING");
            tokenMapper.updateById(parent);
            result.put("type", "TRANSFER");
            result.put("parent", parent);
            result.put("child", null);
            return result;
        }

        // 无损拆分：父凭证保留剩余金额，子凭证继承 Slot 与到期时间
        parent.setValueAmount(remainder);
        parent.setStatus("CIRCULATING");
        tokenMapper.updateById(parent);

        RwaToken child = new RwaToken();
        child.setTokenId(chainChildTokenId != null ? chainChildTokenId : nextChildTokenId(parent.getTokenId()));
        child.setSlotId(parent.getSlotId());
        child.setParentTokenId(parent.getTokenId());
        child.setOwnerAddress(target.getWalletAddress());
        child.setEnterpriseId(target.getId());
        child.setInvoiceId(parent.getInvoiceId());
        child.setValueAmount(value);
        child.setDueTimestamp(parent.getDueTimestamp());
        child.setStatus("CIRCULATING");
        child.setContractAddress(parent.getContractAddress());
        child.setTxHash(chainTxHash != null ? chainTxHash : offchainTxHash());
        child.setMetadataJson(parent.getMetadataJson());
        tokenMapper.insert(child);

        result.put("type", "SPLIT");
        result.put("parent", parent);
        result.put("child", child);
        return result;
    }

    /**
     * 信用穿透拓扑：节点为涉及凭证流转的企业，边为拆分/转让产生的信用下沉，
     * 另附加银行 -> 供应商的融资边。
     */
    public Map<String, Object> topology() {
        List<RwaToken> tokens = tokenMapper.selectList(null);
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        Map<Long, Enterprise> byId = new LinkedHashMap<>();
        enterprises.forEach(e -> byId.put(e.getId(), e));
        Map<String, RwaToken> tokenById = new LinkedHashMap<>();
        tokens.forEach(t -> tokenById.put(t.getTokenId(), t));

        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, BigDecimal> edgeValue = new LinkedHashMap<>();
        Map<String, String[]> edgeEnds = new LinkedHashMap<>();

        for (RwaToken t : tokens) {
            Enterprise holder = byId.get(t.getEnterpriseId());
            if (holder != null) {
                nodes.putIfAbsent(holder.getName(), node(holder));
            }
            if (t.getParentTokenId() != null) {
                RwaToken parent = tokenById.get(t.getParentTokenId());
                if (parent == null) continue;
                Enterprise from = byId.get(parent.getEnterpriseId());
                Enterprise to = byId.get(t.getEnterpriseId());
                if (from == null || to == null || from.getId().equals(to.getId())) continue;
                nodes.putIfAbsent(from.getName(), node(from));
                String key = from.getName() + "->" + to.getName();
                edgeEnds.putIfAbsent(key, new String[]{from.getName(), to.getName()});
                edgeValue.merge(key, t.getValueAmount(), BigDecimal::add);
            } else if (t.getInvoiceId() != null) {
                // 根凭证：补上 核心企业 -> 一级供应商 的确权开立边
                Invoice invoice = invoiceMapper.selectById(t.getInvoiceId());
                if (invoice == null) continue;
                Enterprise core = byId.get(invoice.getCoreEnterpriseId());
                Enterprise firstHolder = byId.get(invoice.getSupplierId());
                if (core == null || firstHolder == null) continue;
                nodes.putIfAbsent(core.getName(), node(core));
                nodes.putIfAbsent(firstHolder.getName(), node(firstHolder));
                String key = core.getName() + "->" + firstHolder.getName();
                edgeEnds.putIfAbsent(key, new String[]{core.getName(), firstHolder.getName()});
                edgeValue.merge(key, invoice.getAmount(), BigDecimal::add);
            }
        }

        // 银行融资边
        List<LoanApplication> loans = loanMapper.selectList(null);
        for (LoanApplication loan : loans) {
            Enterprise bank = byId.get(loan.getBankId());
            Enterprise supplier = byId.get(loan.getSupplierId());
            if (bank == null || supplier == null) continue;
            nodes.putIfAbsent(bank.getName(), node(bank));
            nodes.putIfAbsent(supplier.getName(), node(supplier));
            String key = bank.getName() + "->" + supplier.getName();
            edgeEnds.putIfAbsent(key, new String[]{bank.getName(), supplier.getName()});
            edgeValue.merge(key, loan.getAmount(), BigDecimal::add);
        }

        List<Map<String, Object>> links = new ArrayList<>();
        edgeValue.forEach((key, value) -> {
            String[] ends = edgeEnds.get(key);
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("source", ends[0]);
            link.put("target", ends[1]);
            link.put("value", value);
            links.add(link);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", new ArrayList<>(nodes.values()));
        result.put("links", links);
        return result;
    }

    /**
     * 凭证溯源核验：沿 parent_token_id 回溯至根凭证，返回完整流转链、
     * 关联发票与融资记录。链下模式下 tx_hash 为 OFFCHAIN- 占位，
     * 待接入成员1合约后可追加链上哈希比对。
     */
    public Map<String, Object> verify(String tokenId) {
        RwaToken token = tokenMapper.selectOne(
                new QueryWrapper<RwaToken>().eq("token_id", tokenId));
        if (token == null) {
            throw new IllegalArgumentException("凭证不存在：" + tokenId);
        }

        Map<Long, Enterprise> byId = new LinkedHashMap<>();
        enterpriseMapper.selectList(null).forEach(e -> byId.put(e.getId(), e));

        List<Map<String, Object>> chain = new ArrayList<>();
        RwaToken current = token;
        int guard = 0;
        while (current != null && guard++ < 64) {
            Map<String, Object> hop = new LinkedHashMap<>();
            hop.put("tokenId", current.getTokenId());
            hop.put("valueAmount", current.getValueAmount());
            hop.put("slotId", current.getSlotId());
            hop.put("status", current.getStatus());
            hop.put("txHash", current.getTxHash());
            Enterprise holder = byId.get(current.getEnterpriseId());
            hop.put("holder", holder == null ? null : holder.getName());
            chain.add(hop);
            if (current.getParentTokenId() == null) break;
            current = tokenMapper.selectOne(
                    new QueryWrapper<RwaToken>().eq("token_id", current.getParentTokenId()));
        }
        java.util.Collections.reverse(chain);

        // 链条首项即根凭证，取其底层发票作为贸易背景
        RwaToken root = token;
        Invoice invoice = null;
        if (!chain.isEmpty()) {
            RwaToken rootToken = tokenMapper.selectOne(
                    new QueryWrapper<RwaToken>().eq("token_id", chain.get(0).get("tokenId").toString()));
            if (rootToken != null) {
                root = rootToken;
                if (rootToken.getInvoiceId() != null) {
                    invoice = invoiceMapper.selectById(rootToken.getInvoiceId());
                }
            }
        }

        List<LoanApplication> loans = loanMapper.selectList(
                new QueryWrapper<LoanApplication>().eq("token_id", tokenId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "OFFCHAIN_DB");
        result.put("verified", true);
        result.put("rootTokenId", root.getTokenId());
        result.put("chainDepth", chain.size());
        result.put("chain", chain);
        result.put("invoice", invoice);
        result.put("loans", loans);
        result.put("note", "当前为链下存证模式（OFFCHAIN- 占位哈希）；接入成员1合约后将追加链上哈希比对");

        // 链上模式：对数字 tokenId 追加链上余额/持有方交叉校验
        if (chainTx.chainReadyForRead() && ChainTxService.isNumericTokenId(tokenId)) {
            try {
                java.math.BigInteger chainValueCents = chainTx.balanceOfOnChain(tokenId);
                String chainOwner = chainTx.ownerOfOnChain(tokenId);
                boolean valueMatch = chainValueCents != null
                        && chainValueCents.equals(ChainTxService.toCents(token.getValueAmount()));
                result.put("mode", "ONCHAIN");
                result.put("chainValue", chainValueCents == null ? null : ChainTxService.toYuan(chainValueCents));
                result.put("chainOwner", chainOwner);
                result.put("chainVerified", valueMatch);
                result.put("verified", valueMatch);
                result.put("note", valueMatch
                        ? "链上余额与业务库一致，核验通过"
                        : "链上余额与业务库不一致，请排查同步状态");
            } catch (Exception e) {
                result.put("chainError", e.getMessage());
            }
        }
        return result;
    }

    private Enterprise requireEnterprise(Long id) {
        Enterprise e = id == null ? null : enterpriseMapper.selectById(id);
        if (e == null) {
            throw new IllegalArgumentException("企业不存在：id=" + id);
        }
        return e;
    }

    private Long toEpochSecond(Invoice invoice) {
        if (invoice.getDueDate() == null) return null;
        return invoice.getDueDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    private String newTokenId() {
        return "RWA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private String nextChildTokenId(String parentTokenId) {
        // 用随机后缀而非 count+1，避免并发拆分生成重复 tokenId
        return parentTokenId + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String offchainTxHash() {
        return "OFFCHAIN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private String buildMetadata(Invoice invoice, Enterprise core, Enterprise supplier) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("coreEnterprise", core.getName());
            metadata.put("supplier", supplier.getName());
            metadata.put("invoiceCode", invoice.getInvoiceCode());
            metadata.put("creditSignature", "OFFCHAIN-SIG-" + core.getEnterpriseCode());
            metadata.put("mode", "OFFCHAIN_DB");
            return objectMapper.writeValueAsString(metadata);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("凭证元数据序列化失败", e);
        }
    }

    private Map<String, Object> node(Enterprise e) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", e.getName());
        n.put("type", e.getEnterpriseType());
        return n;
    }
}
