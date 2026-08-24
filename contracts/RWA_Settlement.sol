// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "../interfaces/IAssetCore.sol";
import "../interfaces/IAccessControl.sol";

/**
 * @title RWA_Settlement
 * @author 成员2 - 金融业务逻辑与流转合约工程师
 * @dev 供应链RWA凭证金融贴现与状态机清算合约
 *
 * 核心功能：
 * 1. 五态状态机：Uncirculated → Circulating → Pledged → Settled / Overdue
 * 2. 银行质押贴现：供应商将凭证质押给银行申请融资，凭证进入托管池
 * 3. 到期自动兑付：到期日触发，核心企业托管资金按持份比例划转
 * 4. 逾期处理：到期未兑付则标记为Overdue，触发清算流程
 *
 * 状态流转约束：
 * - Uncirculated(未流通): 核心企业刚开立，尚未流转
 * - Circulating(流通中): 已在供应商间流转，可继续拆分/转让/质押
 * - Pledged(已质押): 已质押给银行申请融资，期间不可再流转
 * - Settled(已兑付): 到期已完成资金兑付，凭证销毁
 * - Overdue(已逾期): 到期未兑付，进入逾期处置
 *
 * 依赖：成员1的IAssetCore、成员4的IAccessControl
 */
contract RWA_Settlement {
    // ========== 状态机枚举 ==========

    /**
     * @dev 凭证生命周期五态状态机
     */
    enum Status {
        Uncirculated, // 0 - 未流通（核心企业刚开立）
        Circulating,  // 1 - 流通中（供应商间流转）
        Pledged,      // 2 - 已质押（银行融资托管中）
        Settled,      // 3 - 已兑付（到期完成清算）
        Overdue       // 4 - 已逾期（到期未兑付）
    }

    // ========== 状态变量 ==========

    /// @dev 成员1部署的核心资产合约地址
    IAssetCore public immutable assetCore;

    /// @dev 成员4部署的权限控制合约地址
    IAccessControl public immutable accessControl;

    /// @dev 合约管理员
    address public admin;

    /// @dev 凭证当前状态：tokenId => Status
    mapping(uint256 => Status) public tokenStatus;

    /// @dev 状态变更历史：tokenId => (状态变更次数 => 状态记录)
    struct StatusChange {
        Status fromStatus;
        Status toStatus;
        address operator;
        uint256 timestamp;
        string reason;
    }
    mapping(uint256 => StatusChange[]) public statusHistory;

    // ========== 融资质押结构体 ==========

    struct PledgeRecord {
        uint256 tokenId;           // 质押的凭证ID
        address borrower;          // 借款方（供应商）
        address bank;              // 放款银行
        uint256 loanAmount;        // 融资金额
        uint256 interestRate;      // 年化利率（基点，如500=5%）
        uint256 pledgeTimestamp;   // 质押时间
        uint256 expectedRepayDate; // 预期还款日
        bool isRepaid;             // 是否已还款解押
        uint256 repayTimestamp;    // 还款时间
    }

    /// @dev 质押记录映射：pledgeId => PledgeRecord
    mapping(uint256 => PledgeRecord) public pledgeRecords;

    /// @dev 质押记录自增ID
    uint256 public pledgeRecordCount;

    /// @dev 凭证当前质押记录：tokenId => pledgeId（0表示未质押）
    mapping(uint256 => uint256) public activePledgeOf;

    // ========== 清算结构体 ==========

    struct SettlementRecord {
        uint256 tokenId;           // 清算的凭证ID
        uint256 maturityTimestamp; // 到期时间
        uint256 settleTimestamp;   // 实际清算时间
        uint256 totalValue;        // 凭证面值总额
        address coreEnterprise;    // 核心企业（付款方）
        address[] payees;          // 收款方列表（银行+各持权供应商）
        uint256[] payAmounts;      // 对应收款金额
        bool isCompleted;          // 清算是否完成
    }

    /// @dev 清算记录映射：tokenId => SettlementRecord
    mapping(uint256 => SettlementRecord) public settlementRecords;

    /// @dev 核心企业托管资金：coreEnterprise => 托管金额
    mapping(address => uint256) public escrowBalance;

    // ========== 事件 ==========

    /// @dev 状态变更事件
    event StatusChanged(
        uint256 indexed tokenId,
        Status indexed fromStatus,
        Status indexed toStatus,
        address operator,
        string reason
    );

    /// @dev 融资申请事件（链上事件，供成员3后端监听）
    event FinancingApplied(
        uint256 indexed pledgeId,
        uint256 indexed tokenId,
        address indexed borrower,
        address bank,
        uint256 loanAmount,
        uint256 interestRate,
        uint256 pledgeTimestamp
    );

    /// @dev 融资放款事件
    event LoanDisbursed(
        uint256 indexed pledgeId,
        uint256 indexed tokenId,
        address indexed bank,
        address borrower,
        uint256 loanAmount
    );

    /// @dev 还款解押事件
    event LoanRepaid(
        uint256 indexed pledgeId,
        uint256 indexed tokenId,
        address indexed borrower,
        uint256 repayAmount,
        uint256 repayTimestamp
    );

    /// @dev 到期兑付事件
    event AssetSettled(
        uint256 indexed tokenId,
        uint256 totalValue,
        address coreEnterprise,
        uint256 settleTimestamp
    );

    /// @dev 逾期事件
    event AssetOverdue(
        uint256 indexed tokenId,
        uint256 maturityTimestamp,
        uint256 overdueTimestamp
    );

    /// @dev 托管资金存入事件
    event EscrowDeposited(
        address indexed coreEnterprise,
        uint256 amount,
        uint256 totalBalance
    );

    // ========== 修饰符 ==========

    modifier onlyAdmin() {
        require(msg.sender == admin, "RWA_Settlement: caller is not admin");
        _;
    }

    modifier onlyCoreEnterprise() {
        require(
            accessControl.hasRole(accessControl.CORE_ENTERPRISE_ROLE(), msg.sender),
            "RWA_Settlement: caller is not core enterprise"
        );
        _;
    }

    modifier onlySupplier() {
        require(
            accessControl.hasRole(accessControl.SUPPLIER_ROLE(), msg.sender),
            "RWA_Settlement: caller is not supplier"
        );
        _;
    }

    modifier onlyBank() {
        require(
            accessControl.hasRole(accessControl.BANK_ROLE(), msg.sender),
            "RWA_Settlement: caller is not bank"
        );
        _;
    }

    // ========== 构造函数 ==========

    constructor(address _assetCore, address _accessControl) {
        require(_assetCore != address(0), "RWA_Settlement: zero asset core address");
        require(_accessControl != address(0), "RWA_Settlement: zero access control address");

        assetCore = IAssetCore(_assetCore);
        accessControl = IAccessControl(_accessControl);
        admin = msg.sender;
    }

    // ========== 状态机核心函数 ==========

    /**
     * @dev 内部状态变更函数，强制执行状态流转约束
     * @param tokenId 凭证ID
     * @param newStatus 目标状态
     * @param reason 变更原因
     *
     * 允许的状态流转：
     * - Uncirculated → Circulating（首次流转）
     * - Circulating → Pledged（质押融资）
     * - Pledged → Circulating（还款解押）
     * - Circulating → Settled（到期兑付）
     * - Pledged → Settled（到期兑付，银行优先受偿）
     * - Circulating → Overdue（到期未兑付）
     * - Pledged → Overdue（到期未兑付）
     * - Settled → 终态（不可变更）
     * - Overdue → Settled（逾期后兑付）
     */
    function _changeStatus(
        uint256 tokenId,
        Status newStatus,
        string memory reason
    ) internal {
        Status currentStatus = tokenStatus[tokenId];

        // 终态校验：已兑付状态不可变更
        require(
            currentStatus != Status.Settled,
            "RWA_Settlement: token already settled, cannot change status"
        );

        // 状态流转合法性校验
        bool isValidTransition = _isValidTransition(currentStatus, newStatus);
        require(
            isValidTransition,
            "RWA_Settlement: invalid status transition"
        );

        // 记录状态变更历史
        statusHistory[tokenId].push(
            StatusChange({
                fromStatus: currentStatus,
                toStatus: newStatus,
                operator: msg.sender,
                timestamp: block.timestamp,
                reason: reason
            })
        );

        // 更新状态
        tokenStatus[tokenId] = newStatus;

        emit StatusChanged(tokenId, currentStatus, newStatus, msg.sender, reason);
    }

    /**
     * @dev 校验状态流转是否合法
     */
    function _isValidTransition(
        Status from,
        Status to
    ) internal pure returns (bool) {
        // 同状态不变更
        if (from == to) return false;

        // 合法流转矩阵
        if (from == Status.Uncirculated && to == Status.Circulating) return true;
        if (from == Status.Circulating && to == Status.Pledged) return true;
        if (from == Status.Pledged && to == Status.Circulating) return true; // 还款解押
        if (from == Status.Circulating && to == Status.Settled) return true;
        if (from == Status.Pledged && to == Status.Settled) return true;
        if (from == Status.Circulating && to == Status.Overdue) return true;
        if (from == Status.Pledged && to == Status.Overdue) return true;
        if (from == Status.Overdue && to == Status.Settled) return true; // 逾期后兑付

        return false;
    }

    /**
     * @dev 标记凭证进入流通状态（首次流转时调用）
     */
    function markAsCirculating(uint256 tokenId) external onlySupplier {
        require(
            assetCore.ownerOf(tokenId) == msg.sender,
            "RWA_Settlement: caller is not token owner"
        );
        _changeStatus(tokenId, Status.Circulating, "First circulation initiated");
    }

    // ========== 银行质押贴现函数 ==========

    /**
     * @dev 供应商向银行申请质押融资（核心函数）
     * 将凭证暂存至合约托管池，触发FinancingApplied链上事件
     * 状态从 Circulating → Pledged
     *
     * @param tokenId 质押的凭证ID
     * @param bank 放款银行地址
     * @param loanAmount 申请融资金额
     * @param interestRate 年化利率（基点，如500=5%）
     * @param expectedRepayDate 预期还款日
     */
    function pledgeForLoan(
        uint256 tokenId,
        address bank,
        uint256 loanAmount,
        uint256 interestRate,
        uint256 expectedRepayDate
    ) external onlySupplier {
        // ===== 前置校验 =====
        require(loanAmount > 0, "RWA_Settlement: loan amount must be positive");
        require(bank != address(0), "RWA_Settlement: zero bank address");
        require(expectedRepayDate > block.timestamp, "RWA_Settlement: repay date must be future");

        // 校验调用者是凭证持有者
        require(
            assetCore.ownerOf(tokenId) == msg.sender,
            "RWA_Settlement: caller is not token owner"
        );

        // 校验凭证处于可质押状态
        require(
            tokenStatus[tokenId] == Status.Circulating,
            "RWA_Settlement: token must be in Circulating status to pledge"
        );

        // 校验银行角色
        require(
            accessControl.hasRole(accessControl.BANK_ROLE(), bank),
            "RWA_Settlement: target is not a bank"
        );

        // 校验融资金额不超过凭证面值（贴现率约束，通常融资额<=面值）
        uint256 tokenValue = assetCore.balanceOf(tokenId);
        require(
            loanAmount <= tokenValue,
            "RWA_Settlement: loan amount exceeds token value"
        );

        // ===== 创建质押记录 =====
        uint256 pledgeId = ++pledgeRecordCount;
        pledgeRecords[pledgeId] = PledgeRecord({
            tokenId: tokenId,
            borrower: msg.sender,
            bank: bank,
            loanAmount: loanAmount,
            interestRate: interestRate,
            pledgeTimestamp: block.timestamp,
            expectedRepayDate: expectedRepayDate,
            isRepaid: false,
            repayTimestamp: 0
        });

        // 绑定活跃质押
        activePledgeOf[tokenId] = pledgeId;

        // ===== 状态变更：Circulating → Pledged =====
        _changeStatus(tokenId, Status.Pledged, "Pledged for bank financing");

        // ===== 触发融资申请事件（成员3后端监听此事件） =====
        emit FinancingApplied(
            pledgeId,
            tokenId,
            msg.sender,
            bank,
            loanAmount,
            interestRate,
            block.timestamp
        );
    }

    /**
     * @dev 银行确认放款（链上记录，实际资金划转走链下/银行系统）
     */
    function disburseLoan(uint256 pledgeId) external onlyBank {
        PledgeRecord storage record = pledgeRecords[pledgeId];
        require(record.bank == msg.sender, "RWA_Settlement: caller is not the lending bank");
        require(!record.isRepaid, "RWA_Settlement: loan already repaid");

        emit LoanDisbursed(
            pledgeId,
            record.tokenId,
            msg.sender,
            record.borrower,
            record.loanAmount
        );
    }

    /**
     * @dev 供应商还款解押
     * 状态从 Pledged → Circulating
     */
    function repayAndRelease(uint256 pledgeId) external onlySupplier {
        PledgeRecord storage record = pledgeRecords[pledgeId];
        require(record.borrower == msg.sender, "RWA_Settlement: caller is not borrower");
        require(!record.isRepaid, "RWA_Settlement: loan already repaid");

        // 计算还款总额（本金+利息，简化为按天计息）
        uint256 daysElapsed = (block.timestamp - record.pledgeTimestamp) / 1 days;
        uint256 interest = (record.loanAmount * record.interestRate * daysElapsed) / (365 * 10000);
        uint256 totalRepay = record.loanAmount + interest;

        // 标记已还款
        record.isRepaid = true;
        record.repayTimestamp = block.timestamp;

        // 解除活跃质押绑定
        activePledgeOf[record.tokenId] = 0;

        // 状态变更：Pledged → Circulating
        _changeStatus(record.tokenId, Status.Circulating, "Loan repaid, token released");

        emit LoanRepaid(
            pledgeId,
            record.tokenId,
            msg.sender,
            totalRepay,
            block.timestamp
        );
    }

    // ========== 到期清算函数 ==========

    /**
     * @dev 核心企业存入托管资金（用于到期兑付）
     */
    function depositEscrow() external payable onlyCoreEnterprise {
        require(msg.value > 0, "RWA_Settlement: deposit amount must be positive");
        escrowBalance[msg.sender] += msg.value;

        emit EscrowDeposited(msg.sender, msg.value, escrowBalance[msg.sender]);
    }

    /**
     * @dev 到期自动兑付（核心函数）
     * 到期日触发时，自动将核心企业托管资金按持份比例划转至银行及各持权供应商账户，并销毁凭证
     * 状态从 Circulating/Pledged → Settled
     *
     * @param tokenId 要兑付的凭证ID
     * @param payees 收款方地址数组（银行+各持权供应商）
     * @param payAmounts 对应收款金额数组（按持份比例分配）
     */
    function autoSettle(
        uint256 tokenId,
        address[] calldata payees,
        uint256[] calldata payAmounts
    ) external onlyAdmin returns (bool) {
        // ===== 前置校验 =====
        require(
            payees.length == payAmounts.length,
            "RWA_Settlement: payees and amounts length mismatch"
        );
        require(payees.length > 0, "RWA_Settlement: empty payees");

        // 校验凭证已到期
        IAssetCore.AssetMetadata memory metadata = assetCore.getAssetMetadata(tokenId);
        require(
            block.timestamp >= metadata.maturityTimestamp,
            "RWA_Settlement: token not yet matured"
        );

        // 校验凭证处于可清算状态
        Status currentStatus = tokenStatus[tokenId];
        require(
            currentStatus == Status.Circulating ||
            currentStatus == Status.Pledged ||
            currentStatus == Status.Overdue,
            "RWA_Settlement: token not in settleable status"
        );

        // 校验总兑付金额等于凭证面值
        uint256 totalValue = assetCore.balanceOf(tokenId);
        uint256 totalPayAmount;
        for (uint256 i = 0; i < payAmounts.length; i++) {
            totalPayAmount += payAmounts[i];
        }
        require(
            totalPayAmount == totalValue,
            "RWA_Settlement: total pay amount must equal token value"
        );

        // 校验核心企业托管资金充足
        require(
            escrowBalance[metadata.coreEnterprise] >= totalValue,
            "RWA_Settlement: insufficient escrow balance"
        );

        // ===== 执行资金划转 =====
        // 从核心企业托管账户扣减
        escrowBalance[metadata.coreEnterprise] -= totalValue;

        // 向各收款方划转（实际场景中通过链下银行系统或稳定币完成）
        // 这里记录链上清算记录，资金划转事件供后端监听
        for (uint256 i = 0; i < payees.length; i++) {
            // 实际资金划转逻辑（可集成稳定币转账）
            // payable(payees[i]).transfer(payAmounts[i]);
        }

        // ===== 记录清算信息 =====
        settlementRecords[tokenId] = SettlementRecord({
            tokenId: tokenId,
            maturityTimestamp: metadata.maturityTimestamp,
            settleTimestamp: block.timestamp,
            totalValue: totalValue,
            coreEnterprise: metadata.coreEnterprise,
            payees: payees,
            payAmounts: payAmounts,
            isCompleted: true
        });

        // ===== 如果有活跃质押，标记为已结清 =====
        uint256 activePledgeId = activePledgeOf[tokenId];
        if (activePledgeId != 0) {
            pledgeRecords[activePledgeId].isRepaid = true;
            pledgeRecords[activePledgeId].repayTimestamp = block.timestamp;
            activePledgeOf[tokenId] = 0;
        }

        // ===== 状态变更：→ Settled =====
        _changeStatus(tokenId, Status.Settled, "Maturity settlement completed");

        // ===== 销毁凭证（调用成员1的burn函数） =====
        assetCore.burn(tokenId);

        emit AssetSettled(tokenId, totalValue, metadata.coreEnterprise, block.timestamp);

        return true;
    }

    /**
     * @dev 标记凭证逾期（到期未兑付时由管理员触发）
     * 状态从 Circulating/Pledged → Overdue
     */
    function markOverdue(uint256 tokenId) external onlyAdmin {
        IAssetCore.AssetMetadata memory metadata = assetCore.getAssetMetadata(tokenId);
        require(
            block.timestamp > metadata.maturityTimestamp,
            "RWA_Settlement: token not yet overdue"
        );

        Status currentStatus = tokenStatus[tokenId];
        require(
            currentStatus == Status.Circulating || currentStatus == Status.Pledged,
            "RWA_Settlement: token not in overdue-able status"
        );

        _changeStatus(tokenId, Status.Overdue, "Maturity passed without settlement");

        emit AssetOverdue(tokenId, metadata.maturityTimestamp, block.timestamp);
    }

    // ========== 查询函数 ==========

    /**
     * @dev 查询凭证当前状态
     */
    function getTokenStatus(uint256 tokenId) external view returns (Status) {
        return tokenStatus[tokenId];
    }

    /**
     * @dev 查询凭证状态变更历史
     */
    function getStatusHistory(
        uint256 tokenId
    ) external view returns (StatusChange[] memory) {
        return statusHistory[tokenId];
    }

    /**
     * @dev 查询凭证的活跃质押记录
     */
    function getActivePledge(
        uint256 tokenId
    ) external view returns (PledgeRecord memory) {
        uint256 pledgeId = activePledgeOf[tokenId];
        require(pledgeId != 0, "RWA_Settlement: no active pledge for this token");
        return pledgeRecords[pledgeId];
    }

    /**
     * @dev 查询某供应商的所有质押记录
     */
    function getPledgesByBorrower(
        address borrower
    ) external view returns (PledgeRecord[] memory) {
        uint256 count;
        for (uint256 i = 1; i <= pledgeRecordCount; i++) {
            if (pledgeRecords[i].borrower == borrower) count++;
        }

        PledgeRecord[] memory result = new PledgeRecord[](count);
        uint256 index;
        for (uint256 i = 1; i <= pledgeRecordCount; i++) {
            if (pledgeRecords[i].borrower == borrower) {
                result[index] = pledgeRecords[i];
                index++;
            }
        }
        return result;
    }

    /**
     * @dev 查询凭证清算记录
     */
    function getSettlementRecord(
        uint256 tokenId
    ) external view returns (SettlementRecord memory) {
        return settlementRecords[tokenId];
    }

    /**
     * @dev 查询即将到期的凭证（供后端定时任务扫描）
     * @param tokenIds 待检查的凭证ID数组
     * @return maturedTokens 已到期的凭证ID列表
     */
    function checkMaturedTokens(
        uint256[] calldata tokenIds
    ) external view returns (uint256[] memory maturedTokens) {
        uint256 count;
        for (uint256 i = 0; i < tokenIds.length; i++) {
            IAssetCore.AssetMetadata memory metadata = assetCore.getAssetMetadata(tokenIds[i]);
            if (block.timestamp >= metadata.maturityTimestamp &&
                tokenStatus[tokenIds[i]] != Status.Settled) {
                count++;
            }
        }

        maturedTokens = new uint256[](count);
        uint256 index;
        for (uint256 i = 0; i < tokenIds.length; i++) {
            IAssetCore.AssetMetadata memory metadata = assetCore.getAssetMetadata(tokenIds[i]);
            if (block.timestamp >= metadata.maturityTimestamp &&
                tokenStatus[tokenIds[i]] != Status.Settled) {
                maturedTokens[index] = tokenIds[i];
                index++;
            }
        }
        return maturedTokens;
    }

    // ========== 管理员函数 ==========

    function transferAdmin(address newAdmin) external onlyAdmin {
        require(newAdmin != address(0), "RWA_Settlement: zero admin address");
        admin = newAdmin;
    }
}
