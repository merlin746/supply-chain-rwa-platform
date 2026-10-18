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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenService {
    private static final List<String> TRANSFERABLE_STATUSES = List.of("UNCIRCULATED", "CIRCULATING");

    private final RwaTokenMapper tokenMapper;
    private final InvoiceMapper invoiceMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final LoanApplicationMapper loanMapper;
    private final ChainTxService chainTx;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${web3.contract-address:}")
    private String contractAddress;

    @Transactional
    public RwaToken mint(MintRequest request, Long actorCoreEnterpriseId) {
        if (request == null || request.invoiceId() == null) {
            throw new IllegalArgumentException("发票不能为空");
        }
        Invoice invoice = lockInvoice(request.invoiceId());
        if (invoice == null) {
            throw new IllegalArgumentException("发票不存在");
        }
        if (actorCoreEnterpriseId == null || !actorCoreEnterpriseId.equals(invoice.getCoreEnterpriseId())) {
            throw new IllegalStateException("只能基于本核心企业的发票开立凭证");
        }
        if (!"APPROVED".equals(invoice.getStatus())) {
            throw new IllegalStateException("发票未审核通过，不能开立凭证（当前状态：" + invoice.getStatus() + "）");
        }
        if (tokenMapper.selectCount(new QueryWrapper<RwaToken>().eq("invoice_id", invoice.getId())) > 0) {
            throw new IllegalStateException("该发票已开立凭证，不能重复确权");
        }

        Enterprise supplier = requireEnterprise(invoice.getSupplierId(), "SUPPLIER", false);
        Enterprise core = requireEnterprise(invoice.getCoreEnterpriseId(), "CORE_ENTERPRISE", true);
        BigDecimal amount = requirePositiveMoney(invoice.getAmount(), "发票金额");
        if (invoice.getDueDate() == null || !invoice.getDueDate().isAfter(LocalDate.now())) {
            throw new IllegalStateException("发票已到期或缺少到期日，不能开立凭证");
        }
        BigDecimal creditUsed = core.getCreditUsed() == null ? BigDecimal.ZERO : core.getCreditUsed();
        BigDecimal creditLimit = core.getCreditLimit();
        if (creditLimit == null || creditUsed.add(amount).compareTo(creditLimit) > 0) {
            throw new IllegalStateException("核心企业可用授信额度不足");
        }

        long maturity = toEpochSecond(invoice);
        String tokenId = newTokenId();
        String txHash = offchainTxHash();
        String slot = "SLOT-" + invoice.getDueDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String metadata = buildMetadata(invoice, core, supplier);

        if (chainTx.isEnabled() && !chainTx.chainReady(core.getId())) {
            throw new IllegalStateException("链上模式缺少有效合约地址或当前核心企业签名密钥");
        }
        if (chainTx.chainReady(core.getId())) {
            try {
                java.math.BigInteger slotNumber = chainTx.computeSlot(core.getWalletAddress(), maturity, 1);
                String contractHash = invoice.getFileHash() != null && !invoice.getFileHash().isBlank()
                        ? invoice.getFileHash() : "OFFCHAIN-FILEHASH-" + invoice.getInvoiceCode();
                String[] result = chainTx.mintOnChain(
                        core.getId(), supplier.getWalletAddress(), slotNumber,
                        ChainTxService.toCents(amount), maturity, contractHash, metadata);
                tokenId = result[0];
                txHash = result[1];
                slot = slotNumber.toString();
            } catch (Exception e) {
                handleChainWriteFailure("链上开立", e);
            }
        }

        RwaToken token = new RwaToken();
        token.setTokenId(tokenId);
        token.setSlotId(slot);
        token.setOwnerAddress(supplier.getWalletAddress());
        token.setEnterpriseId(supplier.getId());
        token.setInvoiceId(invoice.getId());
        token.setValueAmount(amount);
        token.setDueTimestamp(maturity);
        token.setStatus("UNCIRCULATED");
        token.setContractAddress(contractAddress);
        token.setTxHash(txHash);
        token.setMetadataJson(metadata);
        if (tokenMapper.insert(token) != 1) {
            throw new IllegalStateException("凭证落库失败");
        }

        core.setCreditUsed(creditUsed.add(amount));
        if (enterpriseMapper.updateById(core) != 1) {
            throw new IllegalStateException("核心企业授信额度更新失败");
        }
        invoice.setStatus("MINTED");
        if (invoiceMapper.updateById(invoice) != 1) {
            throw new IllegalStateException("发票确权状态更新失败");
        }
        return token;
    }

    @Transactional
    public Map<String, Object> split(SplitRequest request, Long actorEnterpriseId) {
        if (request == null || request.fromTokenId() == null || request.fromTokenId().isBlank()) {
            throw new IllegalArgumentException("来源凭证不能为空");
        }
        RwaToken parent = lockToken(request.fromTokenId().trim());
        if (parent == null) {
            throw new IllegalArgumentException("凭证不存在：" + request.fromTokenId());
        }
        if (actorEnterpriseId == null || !actorEnterpriseId.equals(parent.getEnterpriseId())) {
            throw new IllegalStateException("只能拆分或转让本企业当前持有的凭证");
        }
        requireEnterprise(actorEnterpriseId, "SUPPLIER", false);
        if (!TRANSFERABLE_STATUSES.contains(parent.getStatus())) {
            throw new IllegalStateException("当前状态不允许拆分/转让：" + parent.getStatus());
        }
        if (parent.getDueTimestamp() == null || parent.getDueTimestamp() <= Instant.now().getEpochSecond()) {
            throw new IllegalStateException("凭证已到期或缺少到期时间，不能拆分/转让");
        }
        if (loanMapper.selectCount(new QueryWrapper<LoanApplication>()
                .eq("token_id", parent.getTokenId())
                .in("status", LoanService.ACTIVE_STATUSES)) > 0) {
            throw new IllegalStateException("凭证存在进行中的融资申请，不能拆分/转让");
        }

        Enterprise target = requireEnterprise(request.toEnterpriseId(), "SUPPLIER", false);
        if (target.getId().equals(parent.getEnterpriseId())) {
            throw new IllegalArgumentException("接收方与当前持有方相同，无需拆分");
        }
        if (target.getWalletAddress() == null || target.getWalletAddress().isBlank()) {
            throw new IllegalStateException("接收方未配置钱包地址");
        }
        BigDecimal original = requirePositiveMoney(parent.getValueAmount(), "凭证余额");
        BigDecimal value = requirePositiveMoney(request.value(), "拆分金额");
        if (value.compareTo(original) > 0) {
            throw new IllegalArgumentException("拆分金额超过凭证余额（余额：" + original + "）");
        }
        BigDecimal remainder = original.subtract(value);

        String chainChildTokenId = null;
        String chainTxHash = null;
        if (chainTx.isEnabled() && ChainTxService.isNumericTokenId(parent.getTokenId())
                && !chainTx.chainReady(parent.getEnterpriseId())) {
            throw new IllegalStateException("链上凭证缺少有效合约地址或持有方签名密钥，不能拆分");
        }
        if (chainTx.chainReady(parent.getEnterpriseId())
                && ChainTxService.isNumericTokenId(parent.getTokenId())) {
            try {
                String[] result = chainTx.splitOnChain(
                        parent.getEnterpriseId(), parent.getTokenId(), target.getWalletAddress(),
                        ChainTxService.toCents(value));
                chainChildTokenId = result[0];
                chainTxHash = result[1];
            } catch (Exception e) {
                handleChainWriteFailure("链上拆分", e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // Full-balance transfers also create a descendant: retain the source owner
        // and a zero balance so the ownership lineage remains reconstructable.
        parent.setValueAmount(remainder);
        parent.setStatus("CIRCULATING");
        requireUpdated(tokenMapper.updateById(parent), "父凭证余额更新失败");

        RwaToken child = new RwaToken();
        child.setTokenId(chainChildTokenId != null ? chainChildTokenId : newTokenId());
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
        requireUpdated(tokenMapper.insert(child), "子凭证创建失败");

        result.put("type", remainder.signum() == 0 ? "TRANSFER" : "SPLIT");
        result.put("parent", parent);
        result.put("child", child);
        return result;
    }

    public List<RwaToken> listVisible(String role, Long actorEnterpriseId, String ownerAddress,
                                      Long requestedEnterpriseId, String status) {
        QueryWrapper<RwaToken> query = new QueryWrapper<>();
        if (ownerAddress != null && !ownerAddress.isBlank()) query.eq("owner_address", ownerAddress.trim());
        if (status != null && !status.isBlank()) query.eq("status", status.trim());
        if ("SUPPLIER".equals(role)) {
            query.eq("enterprise_id", actorEnterpriseId);
        } else if ("CORE_ENTERPRISE".equals(role)) {
            List<Long> invoiceIds = invoiceMapper.selectList(new QueryWrapper<Invoice>()
                            .select("id").eq("core_enterprise_id", actorEnterpriseId))
                    .stream().map(Invoice::getId).toList();
            if (invoiceIds.isEmpty()) return List.of();
            query.in("invoice_id", invoiceIds);
        } else if ("BANK".equals(role)) {
            List<String> tokenIds = loanMapper.selectList(new QueryWrapper<LoanApplication>()
                            .select("token_id").eq("bank_id", actorEnterpriseId))
                    .stream().map(LoanApplication::getTokenId).distinct().toList();
            if (tokenIds.isEmpty()) return List.of();
            query.in("token_id", tokenIds);
        } else if ("ADMIN".equals(role)) {
            if (requestedEnterpriseId != null) query.eq("enterprise_id", requestedEnterpriseId);
        } else {
            return List.of();
        }
        query.orderByDesc("id");
        return tokenMapper.selectList(query);
    }

    public RwaToken getVisible(String tokenId, String role, Long actorEnterpriseId) {
        RwaToken token = tokenMapper.selectOne(new QueryWrapper<RwaToken>().eq("token_id", tokenId));
        if (token == null) return null;
        if (!canView(token, role, actorEnterpriseId)) throw new IllegalStateException("无权查看该凭证");
        return token;
    }

    public boolean canView(RwaToken token, String role, Long actorEnterpriseId) {
        if ("ADMIN".equals(role)) return true;
        if (actorEnterpriseId == null) return false;
        if ("SUPPLIER".equals(role)) return actorEnterpriseId.equals(token.getEnterpriseId());
        if ("CORE_ENTERPRISE".equals(role)) {
            Invoice invoice = token.getInvoiceId() == null ? null : invoiceMapper.selectById(token.getInvoiceId());
            return invoice != null && actorEnterpriseId.equals(invoice.getCoreEnterpriseId());
        }
        if ("BANK".equals(role)) {
            return loanMapper.selectCount(new QueryWrapper<LoanApplication>()
                    .eq("token_id", token.getTokenId()).eq("bank_id", actorEnterpriseId)) > 0;
        }
        return false;
    }

    public Map<String, Object> topology(String role, Long actorEnterpriseId) {
        List<RwaToken> tokens = "ADMIN".equals(role)
                ? tokenMapper.selectList(null)
                : listVisible(role, actorEnterpriseId, null, null, null);
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        Map<Long, Enterprise> byId = new LinkedHashMap<>();
        enterprises.forEach(e -> byId.put(e.getId(), e));
        Map<String, RwaToken> tokenById = new LinkedHashMap<>();
        tokens.forEach(t -> tokenById.put(t.getTokenId(), t));
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, BigDecimal> edgeValue = new LinkedHashMap<>();
        Map<String, String[]> edgeEnds = new LinkedHashMap<>();
        for (RwaToken token : tokens) {
            Enterprise holder = byId.get(token.getEnterpriseId());
            if (holder != null) nodes.putIfAbsent(holder.getName(), node(holder));
            if (token.getParentTokenId() != null) {
                RwaToken parent = tokenById.get(token.getParentTokenId());
                if (parent == null) continue;
                Enterprise from = byId.get(parent.getEnterpriseId());
                Enterprise to = byId.get(token.getEnterpriseId());
                if (from == null || to == null || from.getId().equals(to.getId())) continue;
                nodes.putIfAbsent(from.getName(), node(from));
                mergeEdge(edgeValue, edgeEnds, from.getName(), to.getName(), token.getValueAmount());
            } else if (token.getInvoiceId() != null) {
                Invoice invoice = invoiceMapper.selectById(token.getInvoiceId());
                if (invoice == null) continue;
                Enterprise core = byId.get(invoice.getCoreEnterpriseId());
                Enterprise firstHolder = byId.get(invoice.getSupplierId());
                if (core == null || firstHolder == null) continue;
                nodes.putIfAbsent(core.getName(), node(core));
                nodes.putIfAbsent(firstHolder.getName(), node(firstHolder));
                mergeEdge(edgeValue, edgeEnds, core.getName(), firstHolder.getName(), invoice.getAmount());
            }
        }
        QueryWrapper<LoanApplication> loanQuery = new QueryWrapper<LoanApplication>()
                .in("status", "DISBURSED", "SETTLED");
        if ("SUPPLIER".equals(role)) loanQuery.eq("supplier_id", actorEnterpriseId);
        else if ("BANK".equals(role)) loanQuery.eq("bank_id", actorEnterpriseId);
        else if ("CORE_ENTERPRISE".equals(role)) {
            List<String> visibleTokenIds = tokens.stream().map(RwaToken::getTokenId).toList();
            if (visibleTokenIds.isEmpty()) return emptyTopology(nodes, edgeValue, edgeEnds);
            loanQuery.in("token_id", visibleTokenIds);
        }
        for (LoanApplication loan : loanMapper.selectList(loanQuery)) {
            Enterprise bank = byId.get(loan.getBankId());
            Enterprise supplier = byId.get(loan.getSupplierId());
            if (bank == null || supplier == null) continue;
            nodes.putIfAbsent(bank.getName(), node(bank));
            nodes.putIfAbsent(supplier.getName(), node(supplier));
            mergeEdge(edgeValue, edgeEnds, bank.getName(), supplier.getName(), loan.getAmount());
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

    private Map<String, Object> emptyTopology(Map<String, Map<String, Object>> nodes,
                                               Map<String, BigDecimal> edgeValue,
                                               Map<String, String[]> edgeEnds) {
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

    public Map<String, Object> verify(String tokenId) {
        RwaToken token = tokenMapper.selectOne(new QueryWrapper<RwaToken>().eq("token_id", tokenId));
        if (token == null) throw new IllegalArgumentException("凭证不存在：" + tokenId);
        Map<Long, Enterprise> byId = new LinkedHashMap<>();
        enterpriseMapper.selectList(null).forEach(e -> byId.put(e.getId(), e));
        List<RwaToken> lineage = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        RwaToken current = token;
        boolean lineageValid = true;
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (!seen.add(current.getTokenId())) {
                lineageValid = false;
                break;
            }
            lineage.add(current);
            if (current.getParentTokenId() == null) break;
            current = tokenMapper.selectOne(new QueryWrapper<RwaToken>()
                    .eq("token_id", current.getParentTokenId()));
            if (current == null) lineageValid = false;
        }
        if (current != null && current.getParentTokenId() != null && lineage.size() >= 64) lineageValid = false;
        Collections.reverse(lineage);
        RwaToken root = lineage.isEmpty() ? token : lineage.get(0);
        Invoice invoice = root.getInvoiceId() == null ? null : invoiceMapper.selectById(root.getInvoiceId());
        List<Map<String, Object>> chain = new ArrayList<>();
        for (RwaToken item : lineage) {
            Map<String, Object> hop = new LinkedHashMap<>();
            hop.put("tokenId", item.getTokenId());
            hop.put("valueAmount", item.getValueAmount());
            hop.put("slotId", item.getSlotId());
            hop.put("status", item.getStatus());
            hop.put("txHash", item.getTxHash());
            Enterprise itemHolder = byId.get(item.getEnterpriseId());
            hop.put("holder", itemHolder == null ? null : itemHolder.getName());
            chain.add(hop);
        }
        Enterprise holder = byId.get(token.getEnterpriseId());
        boolean dbOwnerMatch = holder != null && token.getOwnerAddress() != null
                && holder.getWalletAddress() != null
                && token.getOwnerAddress().equalsIgnoreCase(holder.getWalletAddress());
        boolean dbValueValid = token.getValueAmount() != null && token.getValueAmount().signum() >= 0;
        boolean databaseVerified = lineageValid && invoice != null && dbOwnerMatch && dbValueValid;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "OFFCHAIN_DB");
        result.put("verified", databaseVerified);
        result.put("rootTokenId", root.getTokenId());
        result.put("chainDepth", chain.size());
        result.put("chain", chain);
        result.put("invoice", invoice);
        result.put("loans", loanMapper.selectList(new QueryWrapper<LoanApplication>().eq("token_id", tokenId)));
        result.put("ownerMatch", dbOwnerMatch);
        result.put("valueMatch", dbValueValid);
        result.put("note", databaseVerified
                ? "链下业务库的持有方、余额与凭证谱系一致"
                : "链下业务数据不完整或不一致，请检查持有方、余额与凭证谱系");

        if (chainTx.chainReadyForRead() && ChainTxService.isNumericTokenId(tokenId)) {
            result.put("mode", "ONCHAIN");
            result.put("chainVerified", false);
            result.put("verified", false);
            try {
                java.math.BigInteger chainValueCents = chainTx.balanceOfOnChain(tokenId);
                String chainOwner = chainTx.ownerOfOnChain(tokenId);
                boolean valueMatch = chainValueCents != null
                        && chainValueCents.equals(ChainTxService.toCents(token.getValueAmount()));
                boolean ownerMatch = chainOwner != null && token.getOwnerAddress() != null
                        && chainOwner.equalsIgnoreCase(token.getOwnerAddress());
                boolean chainVerified = valueMatch && ownerMatch && lineageValid && invoice != null;
                result.put("chainValue", chainValueCents == null ? null : ChainTxService.toYuan(chainValueCents));
                result.put("chainOwner", chainOwner);
                result.put("valueMatch", valueMatch);
                result.put("ownerMatch", ownerMatch);
                result.put("chainVerified", chainVerified);
                result.put("verified", chainVerified);
                result.put("note", chainVerified
                        ? "链上余额和持有方均与业务库一致，核验通过"
                        : "链上余额或持有方与业务库不一致，请排查同步状态");
            } catch (Exception e) {
                result.put("chainError", e.getMessage());
                result.put("note", "链上核验失败，不能判定凭证真实有效");
            }
        }
        return result;
    }

    private void handleChainWriteFailure(String operation, Exception failure) {
        throw new IllegalStateException(operation + "失败：" + failure.getMessage(), failure);
    }

    private Invoice lockInvoice(Long id) {
        return invoiceMapper.selectOne(new QueryWrapper<Invoice>().eq("id", id).last("FOR UPDATE"));
    }

    private RwaToken lockToken(String tokenId) {
        return tokenMapper.selectOne(new QueryWrapper<RwaToken>().eq("token_id", tokenId).last("FOR UPDATE"));
    }

    private Enterprise requireEnterprise(Long id, String expectedType, boolean lock) {
        if (id == null) throw new IllegalArgumentException("企业不能为空");
        QueryWrapper<Enterprise> query = new QueryWrapper<Enterprise>().eq("id", id);
        if (lock) query.last("FOR UPDATE");
        Enterprise enterprise = enterpriseMapper.selectOne(query);
        if (enterprise == null || !expectedType.equals(enterprise.getEnterpriseType())) {
            throw new IllegalArgumentException("企业不存在或类型不正确：id=" + id);
        }
        return enterprise;
    }

    private BigDecimal requirePositiveMoney(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(label + "必须大于 0");
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(label + "最多保留两位小数");
        }
    }

    private long toEpochSecond(Invoice invoice) {
        return invoice.getDueDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    private String newTokenId() {
        return "RWA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
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

    private void mergeEdge(Map<String, BigDecimal> edgeValue, Map<String, String[]> edgeEnds,
                           String source, String target, BigDecimal value) {
        if (value == null) return;
        String key = source + "->" + target;
        edgeEnds.putIfAbsent(key, new String[]{source, target});
        edgeValue.merge(key, value, BigDecimal::add);
    }

    private Map<String, Object> node(Enterprise enterprise) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", enterprise.getName());
        node.put("type", enterprise.getEnterpriseType());
        return node;
    }

    private void requireUpdated(int affectedRows, String message) {
        if (affectedRows != 1) throw new IllegalStateException(message);
    }
}
