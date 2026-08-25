// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "../interfaces/IAssetCore.sol";
import "../interfaces/IAccessControl.sol";

/**
 * @title RWA_Circulation
 * @author 成员2 - 金融业务逻辑与流转合约工程师
 * @dev 供应链应收账款RWA凭证无损拆分与多级流转合约
 *
 * 核心功能：
 * 1. 凭证无损拆分：将大额应收账款凭证拆分为多张小额凭证，金额守恒
 * 2. 多级流转：支持一级供应商→二级供应商→三级供应商的链式支付流转
 * 3. 拆分守恒校验：拆分后金额 + 剩余金额 == 原凭证金额
 * 4. 属性继承：新凭证继承原凭证的到期时间戳(Slot)与核心企业信用背书
 *
 * 依赖：成员1的IAssetCore（ERC-3525底层）、成员4的IAccessControl（权限控制）
 */
contract RWA_Circulation {
    // ========== 状态变量 ==========

    /// @dev 成员1部署的核心资产合约地址
    IAssetCore public immutable assetCore;

    /// @dev 成员4部署的权限控制合约地址
    IAccessControl public immutable accessControl;

    /// @dev 合约管理员（用于升级参数等）
    address public admin;

    // ========== 拆分记录结构体 ==========

    struct SplitRecord {
        uint256 originalTokenId;   // 原始凭证ID
        uint256 fromTokenId;       // 拆分后剩余凭证ID（原Token保留剩余面值）
        uint256 toTokenId;         // 新生成的子凭证ID
        uint256 splitValue;        // 拆分出去的金额
        uint256 remainingValue;    // 原凭证剩余金额
        address splitter;          // 拆分操作人（原凭证持有者）
        address recipient;         // 子凭证接收方
        uint256 timestamp;         // 拆分时间戳
        uint256 slot;              // 继承的到期日Slot
    }

    /// @dev 拆分记录映射：splitRecordId => SplitRecord
    mapping(uint256 => SplitRecord) public splitRecords;

    /// @dev 拆分记录自增ID
    uint256 public splitRecordCount;

    /// @dev 凭证拆分溯源：tokenId => 父凭证tokenId（0表示原始开立凭证）
    mapping(uint256 => uint256) public parentOf;

    /// @dev 凭证拆分层级：tokenId => 拆分深度（原始凭证为0）
    mapping(uint256 => uint256) public splitLevelOf;

    // ========== 事件 ==========

    /// @dev 凭证拆分事件
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

    /// @dev 凭证流转事件（多级供应商间支付）
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
            accessControl.hasRole(accessControl.SUPPLIER_ROLE(), msg.sender),
            "RWA_Circulation: caller is not a supplier"
        );
        _;
    }

    // ========== 构造函数 ==========

    /**
     * @param _assetCore 成员1部署的RWA核心资产合约地址
     * @param _accessControl 成员4部署的权限控制合约地址
     */
    constructor(address _assetCore, address _accessControl) {
        require(_assetCore != address(0), "RWA_Circulation: zero asset core address");
        require(_accessControl != address(0), "RWA_Circulation: zero access control address");

        assetCore = IAssetCore(_assetCore);
        accessControl = IAccessControl(_accessControl);
        admin = msg.sender;
    }

    // ========== 核心业务函数 ==========

    /**
     * @dev 凭证无损拆分与流转（核心函数）
     * 基于ERC-3525的transferFromValue逻辑，将fromTokenId中的value面值
     * 拆分转移到toTokenId，原凭证保留剩余面值，新凭证继承原凭证的Slot
     * （到期日）与核心企业信用背书属性。
     * 接收方自动取toTokenId的当前持有者，符合ERC-3525半同质化代币设计。
     *
     * @param fromTokenId 原始凭证ID（拆分方持有，拆分后保留剩余面值）
     * @param toTokenId 目标凭证ID（接收方持有，需预先mint同Slot空凭证）
     * @param value 要拆分转移的金额
     *
     * 拆分守恒校验：
     * - 拆分前原凭证余额 >= value
     * - 拆分后原凭证余额 = 原余额 - value
     * - 新凭证余额 = value
     * - 严格验证：拆分后金额 + 剩余金额 == 原凭证金额
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
        require(
            assetCore.ownerOf(fromTokenId) == msg.sender,
            "RWA_Circulation: caller is not the token owner"
        );

        // 接收方自动取toTokenId的持有者（ERC-3525设计）
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
            accessControl.hasRole(accessControl.SUPPLIER_ROLE(), recipient),
            "RWA_Circulation: recipient is not a supplier"
        );

        // ===== Slot一致性校验（属性继承） =====
        uint256 fromSlot = assetCore.slotOf(fromTokenId);
        uint256 toSlot = assetCore.slotOf(toTokenId);
        require(
            fromSlot == toSlot,
            "RWA_Circulation: slot mismatch - tokens must share same maturity"
        );

        // ===== 执行ERC-3525价值转移（拆分守恒由底层保证） =====
        // transferFromValue会自动从fromTokenId扣除value，增加到toTokenId
        // 底层保证：balanceOf(fromTokenId) + balanceOf(toTokenId) 守恒
        assetCore.transferFromValue(fromTokenId, toTokenId, value);

        // ===== 计算拆分后余额（守恒校验） =====
        uint256 remainingValue = assetCore.balanceOf(fromTokenId);
        uint256 transferredValue = assetCore.balanceOf(toTokenId);

        // 严格守恒校验：拆分后金额 + 剩余金额 == 原凭证金额
        require(
            remainingValue + transferredValue == originalBalance,
            "RWA_Circulation: split conservation check failed"
        );
        require(
            transferredValue == value,
            "RWA_Circulation: transferred value mismatch"
        );

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
     * @param toTokenIds 目标凭证ID数组（各目标凭证需预先mint同Slot空凭证）
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
     * @param tokenId 要查询的凭证ID
     * @return lineage 溯源链路数组（从原始到当前）
     */
    function getSplitLineage(uint256 tokenId) external view returns (uint256[] memory lineage) {
        // 计算链路深度
        uint256 depth = splitLevelOf[tokenId];
        lineage = new uint256[](depth + 1);

        uint256 current = tokenId;
        for (uint256 i = depth; i > 0; i--) {
            lineage[i] = current;
            current = parentOf[current];
        }
        lineage[0] = current; // 原始凭证

        return lineage;
    }

    /**
     * @dev 查询凭证是否为拆分生成的子凭证
     */
    function isSplitChild(uint256 tokenId) external view returns (bool) {
        return parentOf[tokenId] != 0;
    }

    /**
     * @dev 查询某凭证的所有直接子凭证拆分记录
     */
    function getSplitRecordsByToken(
        uint256 tokenId
    ) external view returns (SplitRecord[] memory records) {
        // 先统计数量
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

    /**
     * @dev 转移管理员权限
     */
    function transferAdmin(address newAdmin) external onlyAdmin {
        require(newAdmin != address(0), "RWA_Circulation: zero admin address");
        admin = newAdmin;
    }
}
