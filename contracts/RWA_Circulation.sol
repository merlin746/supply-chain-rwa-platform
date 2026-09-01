// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import "../interfaces/IAssetCore.sol";

/**
 * @title RWA_Circulation
 * @author 成员2 - 金融业务逻辑与流转合约工程师
 * @dev 供应链应收账款RWA凭证无损拆分与多级流转合约
 *
 * 与成员1 RWA_Core_Asset 接口完全对齐：
 * - 价值转移使用 transferFrom(uint256,uint256,uint256)（ERC-3525标准）
 * - 角色权限通过 assetCore.hasRole 查询（成员1合约已继承AccessControl）
 * - 资产信息通过 assetCore.getAssetInfo 查询
 *
 * 核心功能：
 * 1. splitAndTransfer: 凭证无损拆分，严格金额守恒（增量校验法）
 * 2. batchSplitAndTransfer: 批量拆分（一对多支付）
 * 3. 质押保护：已质押给Settlement合约的凭证不可再拆分/转让
 * 4. 拆分溯源：记录父凭证与拆分层级，支持信用穿透查询
 */
contract RWA_Circulation {
    // ========== 状态变量 ==========

    /// @dev 成员1部署的RWA核心资产合约
    IAssetCore public immutable assetCore;

    /// @dev 成员2部署的清算合约地址（用于质押状态检查）
    address public immutable settlementContract;

    /// @dev 合约管理员
    address public admin;

    // ========== 拆分记录 ==========

    struct SplitRecord {
        uint256 originalTokenId;
        uint256 fromTokenId;
        uint256 toTokenId;
        uint256 splitValue;
        uint256 remainingValue;
        address splitter;
        address recipient;
        uint256 timestamp;
        uint256 slot;
    }

    mapping(uint256 => SplitRecord) public splitRecords;
    uint256 public splitRecordCount;

    /// @dev tokenId => 父凭证tokenId（0表示原始开立凭证）
    mapping(uint256 => uint256) public parentOf;

    /// @dev tokenId => 拆分深度（原始凭证为0）
    mapping(uint256 => uint256) public splitLevelOf;

    // ========== 事件 ==========

    event AssetSplit(
        uint256 indexed recordId,
        uint256 indexed originalTokenId,
        uint256 indexed toTokenId,
        uint256 splitValue,
        uint256 remainingValue,
        address splitter,
        address recipient,
        uint256 slot
    );

    event AssetCirculated(
        uint256 indexed fromTokenId,
        uint256 indexed toTokenId,
        uint256 value,
        address indexed fromSupplier,
        address toSupplier,
        uint256 level
    );

    // ========== 修饰符 ==========

    modifier onlyAdmin() {
        require(msg.sender == admin, "RWA_Circulation: caller is not admin");
        _;
    }

    modifier onlySupplier() {
        require(
            assetCore.hasRole(assetCore.SUPPLIER_ROLE(), msg.sender),
            "RWA_Circulation: caller is not a supplier"
        );
        _;
    }

    // ========== 构造函数 ==========

    /**
     * @param _assetCore 成员1 RWA_Core_Asset 合约地址
     * @param _settlementContract 成员2 RWA_Settlement 合约地址（用于质押保护检查）
     */
    constructor(address _assetCore, address _settlementContract) {
        require(_assetCore != address(0), "RWA_Circulation: zero asset core address");
        require(_settlementContract != address(0), "RWA_Circulation: zero settlement address");

        assetCore = IAssetCore(_assetCore);
        settlementContract = _settlementContract;
        admin = msg.sender;
    }

    // ========== 核心业务函数 ==========

    /**
     * @dev 凭证无损拆分与流转（核心函数，3参数符合任务要求）
     * 将 fromTokenId 中的 value 面值拆分转移到 toTokenId。
     * 接收方自动取 toTokenId 的当前持有者（ERC-3525设计）。
     *
     * 拆分守恒校验（增量法，修复审查问题#2）：
     * - 转移前记录 toTokenId 余额 toBalanceBefore
     * - 转移后记录 toTokenId 余额 toBalanceAfter
     * - 严格校验 toBalanceAfter - toBalanceBefore == value
     * - 同时校验 fromTokenId 减少量 == value
     * - 不依赖 toTokenId 初始余额为0，批量拆分重复传同一 toTokenId 也能正确校验
     *
     * 质押保护（修复审查问题#3）：
     * - 如果 fromTokenId 的持有者是 settlementContract，说明凭证已被质押托管，拒绝拆分
     *
     * @param fromTokenId 原始凭证ID（拆分方持有，拆分后保留剩余面值）
     * @param toTokenId 目标凭证ID（接收方持有，需预先mint同Slot凭证）
     * @param value 要拆分转移的金额
     *
     * 前置条件：调用者需先对 assetCore 授权价值：
     *   assetCore.approve(fromTokenId, address(this), value)
     *   或 assetCore.setApprovalForAll(address(this), true)
     */
    function splitAndTransfer(
        uint256 fromTokenId,
        uint256 toTokenId,
        uint256 value
    ) public onlySupplier returns (uint256 recordId) {
        // ===== 前置校验 =====
        require(value > 0, "RWA_Circulation: split value must be positive");
        require(fromTokenId != toTokenId, "RWA_Circulation: cannot split to same token");

        // 校验调用者是原凭证持有者（最优先权限校验）
        address fromOwner = assetCore.ownerOf(fromTokenId);
        require(
            fromOwner == msg.sender,
            "RWA_Circulation: caller is not the token owner"
        );

        // 质押保护：凭证已被质押托管到Settlement合约则不可拆分（修复审查问题#3）
        require(
            fromOwner != settlementContract,
            "RWA_Circulation: token is pledged and cannot be split"
        );

        // 接收方自动取 toTokenId 的持有者（ERC-3525设计）
        address recipient = assetCore.ownerOf(toTokenId);
        require(recipient != address(0), "RWA_Circulation: toTokenId has no owner");
        require(recipient != msg.sender, "RWA_Circulation: cannot split to self");

        // 校验原凭证余额充足
        uint256 originalBalance = assetCore.balanceOf(fromTokenId);
        require(
            originalBalance >= value,
            "RWA_Circulation: insufficient token balance"
        );

        // 校验接收方拥有供应商角色
        require(
            assetCore.hasRole(assetCore.SUPPLIER_ROLE(), recipient),
            "RWA_Circulation: recipient is not a supplier"
        );

        // ===== Slot一致性校验（属性继承：新凭证继承原凭证到期日） =====
        uint256 fromSlot = assetCore.slotOf(fromTokenId);
        uint256 toSlot = assetCore.slotOf(toTokenId);
        require(
            fromSlot == toSlot,
            "RWA_Circulation: slot mismatch - tokens must share same maturity"
        );

        // ===== 守恒校验：记录转移前余额（增量法，修复审查问题#2） =====
        uint256 fromBalanceBefore = assetCore.balanceOf(fromTokenId);
        uint256 toBalanceBefore = assetCore.balanceOf(toTokenId);

        // ===== 执行ERC-3525价值转移（成员1标准接口 transferFrom） =====
        // 成员1合约内部保证：fromTokenId扣减value，toTokenId增加value，同Slot校验
        assetCore.transferFrom(fromTokenId, toTokenId, value);

        // ===== 守恒校验：转移后增量校验（严格保证拆分守恒） =====
        uint256 fromBalanceAfter = assetCore.balanceOf(fromTokenId);
        uint256 toBalanceAfter = assetCore.balanceOf(toTokenId);

        // 校验1：fromTokenId 减少量 == value
        require(
            fromBalanceBefore - fromBalanceAfter == value,
            "RWA_Circulation: from token decrement mismatch"
        );
        // 校验2：toTokenId 增加量 == value（不依赖初始余额为0，批量拆分安全）
        require(
            toBalanceAfter - toBalanceBefore == value,
            "RWA_Circulation: to token increment mismatch"
        );
        // 校验3：总量守恒（原余额 == 剩余 + 增量）
        require(
            fromBalanceAfter + (toBalanceAfter - toBalanceBefore) == originalBalance,
            "RWA_Circulation: split conservation check failed"
        );

        uint256 remainingValue = fromBalanceAfter;

        // ===== 记录拆分溯源 =====
        recordId = ++splitRecordCount;
        splitRecords[recordId] = SplitRecord({
            originalTokenId: fromTokenId,
            fromTokenId: fromTokenId,
            toTokenId: toTokenId,
            splitValue: value,
            remainingValue: remainingValue,
            splitter: msg.sender,
            recipient: recipient,
            timestamp: block.timestamp,
            slot: fromSlot
        });

        // 设置子凭证的父凭证与层级
        parentOf[toTokenId] = fromTokenId;
        splitLevelOf[toTokenId] = splitLevelOf[fromTokenId] + 1;

        // ===== 触发事件 =====
        emit AssetSplit(
            recordId,
            fromTokenId,
            toTokenId,
            value,
            remainingValue,
            msg.sender,
            recipient,
            fromSlot
        );

        emit AssetCirculated(
            fromTokenId,
            toTokenId,
            value,
            msg.sender,
            recipient,
            splitLevelOf[toTokenId]
        );

        return recordId;
    }

    /**
     * @dev 批量拆分：将一张凭证拆分为多张子凭证（一对多支付场景）
     * @param fromTokenId 原始凭证ID
     * @param toTokenIds 目标凭证ID数组（各目标凭证需预先mint同Slot凭证）
     * @param values 对应拆分金额数组
     */
    function batchSplitAndTransfer(
        uint256 fromTokenId,
        uint256[] calldata toTokenIds,
        uint256[] calldata values
    ) external onlySupplier returns (uint256[] memory recordIds) {
        require(
            toTokenIds.length == values.length,
            "RWA_Circulation: array length mismatch"
        );
        require(toTokenIds.length > 0, "RWA_Circulation: empty batch");

        // 校验总拆分金额不超过原凭证余额
        uint256 totalSplitValue;
        for (uint256 i = 0; i < values.length; i++) {
            totalSplitValue += values[i];
        }
        require(
            assetCore.balanceOf(fromTokenId) >= totalSplitValue,
            "RWA_Circulation: total split value exceeds balance"
        );

        recordIds = new uint256[](toTokenIds.length);
        for (uint256 i = 0; i < toTokenIds.length; i++) {
            recordIds[i] = splitAndTransfer(
                fromTokenId,
                toTokenIds[i],
                values[i]
            );
        }

        return recordIds;
    }

    // ========== 查询函数 ==========

    /**
     * @dev 查询凭证的完整拆分溯源链路（从原始凭证到当前凭证）
     */
    function getSplitLineage(uint256 tokenId) external view returns (uint256[] memory lineage) {
        uint256 depth = splitLevelOf[tokenId];
        lineage = new uint256[](depth + 1);

        uint256 current = tokenId;
        for (uint256 i = depth; i > 0; i--) {
            lineage[i] = current;
            current = parentOf[current];
        }
        lineage[0] = current;

        return lineage;
    }

    function isSplitChild(uint256 tokenId) external view returns (bool) {
        return parentOf[tokenId] != 0;
    }

    function getSplitRecordsByToken(
        uint256 tokenId
    ) external view returns (SplitRecord[] memory records) {
        uint256 count;
        for (uint256 i = 1; i <= splitRecordCount; i++) {
            if (splitRecords[i].originalTokenId == tokenId) {
                count++;
            }
        }

        records = new SplitRecord[](count);
        uint256 index;
        for (uint256 i = 1; i <= splitRecordCount; i++) {
            if (splitRecords[i].originalTokenId == tokenId) {
                records[index] = splitRecords[i];
                index++;
            }
        }

        return records;
    }

    // ========== 管理员函数 ==========

    function transferAdmin(address newAdmin) external onlyAdmin {
        require(newAdmin != address(0), "RWA_Circulation: zero admin address");
        admin = newAdmin;
    }
}
