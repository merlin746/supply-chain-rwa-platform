// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import "../interfaces/IAssetCore.sol";

/**
 * @title RWA_Settlement
 * @author 成员2 - 金融业务逻辑与流转合约工程师
 * @dev 供应链RWA凭证金融贴现与状态机清算合约
 *
 * 与成员1 RWA_Core_Asset 接口完全对齐：
 * - 资产信息查询使用 getAssetInfo（返回tuple）
 * - 到期结算使用 settleAsset（需本合约被授予 FINANCIAL_INSTITUTION_ROLE）
 * - 银行角色使用 FINANCIAL_INSTITUTION_ROLE（非 BANK_ROLE）
 * - NFT所有权转移使用 transferFrom(address,address,uint256)
 *
 * 五态状态机：
 *   Uncirculated(未流通) → Circulating(流通中) → Pledged(已质押) → Settled(已兑付)
 *                                                              ↘ Overdue(已逾期)
 *
 * 关键设计（修复审查问题）：
 * - #3 质押真正托管：pledgeForLoan 时将凭证NFT所有权转移到本合约托管，还款时转回
 * - #4 资金清算：链上仅做状态变更与事件记录，实际资金划转由链下银行系统根据
 *   链上 AssetSettled 事件执行；去掉了虚假扣减的 escrowBalance，避免账实不符
 * - #1 接口完全对齐成员1实际ABI
 */
contract RWA_Settlement {
    // ========== 状态机枚举 ==========

    enum Status {
        Uncirculated, // 0 - 未流通（核心企业刚开立）
        Circulating,  // 1 - 流通中（供应商间流转）
        Pledged,      // 2 - 已质押（银行融资托管中）
        Settled,      // 3 - 已兑付（到期完成清算）
        Overdue       // 4 - 已逾期（到期未兑付）
    }

    // ========== 状态变量 ==========

    IAssetCore public immutable assetCore;
    address public admin;

    /// @dev tokenId => 当前业务状态
    mapping(uint256 => Status) public tokenStatus;

    // ========== 状态变更历史 ==========

    struct StatusChange {
        Status fromStatus;
        Status toStatus;
        address operator;
        uint256 timestamp;
        string reason;
    }
    mapping(uint256 => StatusChange[]) public statusHistory;

    // ========== 融资质押记录 ==========

    struct PledgeRecord {
        uint256 tokenId;
        address borrower;          // 借款方（供应商）
        address bank;              // 放款金融机构
        uint256 loanAmount;        // 融资金额
        uint256 interestRate;      // 年化利率（基点，500=5%）
        uint256 pledgeTimestamp;
        uint256 expectedRepayDate;
        bool isRepaid;
        uint256 repayTimestamp;
    }

    mapping(uint256 => PledgeRecord) public pledgeRecords; // pledgeId => record
    uint256 public pledgeRecordCount;
    mapping(uint256 => uint256) public activePledgeOf;     // tokenId => pledgeId（0表示未质押）

    // ========== 清算记录 ==========

    struct SettlementRecord {
        uint256 tokenId;
        uint256 maturityTimestamp;
        uint256 settleTimestamp;
        uint256 totalValue;
        address coreEnterprise;
        address[] payees;         // 收款方列表（链下银行据此执行资金划转）
        uint256[] payAmounts;    // 对应收款金额（按持份比例）
        bool isCompleted;
    }

    mapping(uint256 => SettlementRecord) public settlementRecords;

    // ========== 事件 ==========

    event StatusChanged(
        uint256 indexed tokenId,
        Status indexed fromStatus,
        Status indexed toStatus,
        address operator,
        string reason
    );

    /// @dev 融资申请事件（成员3后端监听，联动链下银行放款）
    event FinancingApplied(
        uint256 indexed pledgeId,
        uint256 indexed tokenId,
        address indexed borrower,
        address bank,
        uint256 loanAmount,
        uint256 interestRate,
        uint256 pledgeTimestamp
    );

    event LoanDisbursed(
        uint256 indexed pledgeId,
        uint256 indexed tokenId,
        address indexed bank,
        address borrower,
        uint256 loanAmount
    );

    event LoanRepaid(
        uint256 indexed pledgeId,
        uint256 indexed tokenId,
        address indexed borrower,
        uint256 repayAmount,
        uint256 repayTimestamp
    );

    /// @dev 到期清算事件（链下银行系统监听此事件，按payees/payAmounts执行实际资金划转）
    event AssetSettled(
        uint256 indexed tokenId,
        uint256 totalValue,
        address coreEnterprise,
        uint256 settleTimestamp,
        address[] payees,
        uint256[] payAmounts
    );

    event AssetOverdue(
        uint256 indexed tokenId,
        uint256 maturityTimestamp,
        uint256 overdueTimestamp
    );

    // ========== 修饰符 ==========

    modifier onlyAdmin() {
        require(msg.sender == admin, "RWA_Settlement: caller is not admin");
        _;
    }

    modifier onlySupplier() {
        require(
            assetCore.hasRole(assetCore.SUPPLIER_ROLE(), msg.sender),
            "RWA_Settlement: caller is not supplier"
        );
        _;
    }

    modifier onlyFinancialInstitution() {
        require(
            assetCore.hasRole(assetCore.FINANCIAL_INSTITUTION_ROLE(), msg.sender),
            "RWA_Settlement: caller is not financial institution"
        );
        _;
    }

    // ========== 构造函数 ==========

    constructor(address _assetCore) {
        require(_assetCore != address(0), "RWA_Settlement: zero asset core address");
        assetCore = IAssetCore(_assetCore);
        admin = msg.sender;
    }

    // ========== 状态机核心 ==========

    /**
     * @dev 内部状态变更，强制执行流转约束
     *
     * 合法流转矩阵：
     *   Uncirculated → Circulating（首次流转）
     *   Circulating  → Pledged（质押融资）
     *   Pledged      → Circulating（还款解押）
     *   Circulating/Pledged → Settled（到期兑付）
     *   Circulating/Pledged → Overdue（到期未付）
     *   Overdue      → Settled（逾期后兑付）
     *   Settled      → 终态（不可变更）
     */
    function _changeStatus(uint256 tokenId, Status newStatus, string memory reason) internal {
        Status currentStatus = tokenStatus[tokenId];

        require(
            currentStatus != Status.Settled,
            "RWA_Settlement: token already settled, cannot change status"
        );
        require(_isValidTransition(currentStatus, newStatus), "RWA_Settlement: invalid status transition");

        statusHistory[tokenId].push(
            StatusChange({
                fromStatus: currentStatus,
                toStatus: newStatus,
                operator: msg.sender,
                timestamp: block.timestamp,
                reason: reason
            })
        );

        tokenStatus[tokenId] = newStatus;
        emit StatusChanged(tokenId, currentStatus, newStatus, msg.sender, reason);
    }

    function _isValidTransition(Status from, Status to) internal pure returns (bool) {
        if (from == to) return false;
        if (from == Status.Uncirculated && to == Status.Circulating) return true;
        if (from == Status.Circulating && to == Status.Pledged) return true;
        if (from == Status.Pledged && to == Status.Circulating) return true;
        if (from == Status.Circulating && to == Status.Settled) return true;
        if (from == Status.Pledged && to == Status.Settled) return true;
        if (from == Status.Circulating && to == Status.Overdue) return true;
        if (from == Status.Pledged && to == Status.Overdue) return true;
        if (from == Status.Overdue && to == Status.Settled) return true;
        return false;
    }

    function markAsCirculating(uint256 tokenId) external onlySupplier {
        require(
            assetCore.ownerOf(tokenId) == msg.sender,
            "RWA_Settlement: caller is not token owner"
        );
        _changeStatus(tokenId, Status.Circulating, "First circulation initiated");
    }

    // ========== 质押融资（真正托管，修复审查问题#3） ==========

    /**
     * @dev 供应商向金融机构申请质押融资
     *
     * 真正的凭证托管（修复审查问题#3）：
     * 1. 校验凭证处于 Circulating 状态
     * 2. 将凭证NFT所有权从借款人转移到本合约托管（transferFrom）
     * 3. 状态变更为 Pledged
     * 4. 触发 FinancingApplied 事件，供成员3后端监听联动链下银行放款
     *
     * 前置条件：借款人需先对本合约授权NFT转移：
     *   assetCore.approve(address(this), tokenId)
     *   或 assetCore.setApprovalForAll(address(this), true)
     *
     * @param tokenId 质押的凭证ID
     * @param bank 放款金融机构地址
     * @param loanAmount 申请融资金额
     * @param interestRate 年化利率（基点）
     * @param expectedRepayDate 预期还款日
     */
    function pledgeForLoan(
        uint256 tokenId,
        address bank,
        uint256 loanAmount,
        uint256 interestRate,
        uint256 expectedRepayDate
    ) external onlySupplier {
        require(loanAmount > 0, "RWA_Settlement: loan amount must be positive");
        require(bank != address(0), "RWA_Settlement: zero bank address");
        require(expectedRepayDate > block.timestamp, "RWA_Settlement: repay date must be future");

        address borrower = msg.sender;

        // 校验调用者是凭证持有者
        require(
            assetCore.ownerOf(tokenId) == borrower,
            "RWA_Settlement: caller is not token owner"
        );

        // 校验凭证处于可质押状态
        require(
            tokenStatus[tokenId] == Status.Circulating,
            "RWA_Settlement: token must be in Circulating status to pledge"
        );

        // 校验金融机构角色
        require(
            assetCore.hasRole(assetCore.FINANCIAL_INSTITUTION_ROLE(), bank),
            "RWA_Settlement: target is not a financial institution"
        );

        // 校验融资金额不超过凭证面值
        uint256 tokenValue = assetCore.balanceOf(tokenId);
        require(loanAmount <= tokenValue, "RWA_Settlement: loan amount exceeds token value");

        // ===== 真正托管：将凭证NFT所有权转移到本合约（修复审查问题#3） =====
        // 借款人需预先 approve(address(this), tokenId) 或 setApprovalForAll
        assetCore.transferFrom(borrower, address(this), tokenId);

        // ===== 创建质押记录 =====
        uint256 pledgeId = ++pledgeRecordCount;
        pledgeRecords[pledgeId] = PledgeRecord({
            tokenId: tokenId,
            borrower: borrower,
            bank: bank,
            loanAmount: loanAmount,
            interestRate: interestRate,
            pledgeTimestamp: block.timestamp,
            expectedRepayDate: expectedRepayDate,
            isRepaid: false,
            repayTimestamp: 0
        });

        activePledgeOf[tokenId] = pledgeId;

        // ===== 状态变更：Circulating → Pledged =====
        _changeStatus(tokenId, Status.Pledged, "Pledged for bank financing");

        // ===== 触发融资申请事件（成员3后端监听，联动链下银行放款） =====
        emit FinancingApplied(
            pledgeId,
            tokenId,
            borrower,
            bank,
            loanAmount,
            interestRate,
            block.timestamp
        );
    }

    /**
     * @dev 金融机构确认放款（链上记录，实际资金走链下银行系统）
     */
    function disburseLoan(uint256 pledgeId) external onlyFinancialInstitution {
        PledgeRecord storage record = pledgeRecords[pledgeId];
        require(record.bank == msg.sender, "RWA_Settlement: caller is not the lending bank");
        require(!record.isRepaid, "RWA_Settlement: loan already repaid");

        emit LoanDisbursed(pledgeId, record.tokenId, msg.sender, record.borrower, record.loanAmount);
    }

    /**
     * @dev 借款人还款解押：将托管的凭证NFT转回借款人
     */
    function repayAndRelease(uint256 pledgeId) external onlySupplier {
        PledgeRecord storage record = pledgeRecords[pledgeId];
        require(record.borrower == msg.sender, "RWA_Settlement: caller is not borrower");
        require(!record.isRepaid, "RWA_Settlement: loan already repaid");

        // 计算还款总额（本金+利息，按天计息，简化计算）
        uint256 daysElapsed = (block.timestamp - record.pledgeTimestamp) / 1 days;
        uint256 interest = (record.loanAmount * record.interestRate * daysElapsed) / (365 * 10000);
        uint256 totalRepay = record.loanAmount + interest;

        // 标记已还款
        record.isRepaid = true;
        record.repayTimestamp = block.timestamp;
        activePledgeOf[record.tokenId] = 0;

        // ===== 将托管的凭证NFT转回借款人（真正解押） =====
        assetCore.transferFrom(address(this), msg.sender, record.tokenId);

        // 状态变更：Pledged → Circulating
        _changeStatus(record.tokenId, Status.Circulating, "Loan repaid, token released");

        emit LoanRepaid(pledgeId, record.tokenId, msg.sender, totalRepay, block.timestamp);
    }

    // ========== 到期清算（链上状态+链下资金，修复审查问题#4） ==========

    /**
     * @dev 到期自动兑付
     *
     * 链上链下分离设计（修复审查问题#4）：
     * - 链上：调用成员1 settleAsset 将资产状态标记为 Settled，记录清算信息，
     *         触发 AssetSettled 事件（含收款方列表和金额）
     * - 链下：银行系统监听 AssetSettled 事件，按 payees/payAmounts 执行实际资金划转
     * - 不再使用虚假的 escrowBalance 扣减，避免账实不符和资金锁死
     *
     * 前置条件：本合约需被成员1授予 FINANCIAL_INSTITUTION_ROLE（才能调用 settleAsset）
     *
     * @param tokenId 要兑付的凭证ID
     * @param payees 收款方地址数组（金融机构+各持权供应商）
     * @param payAmounts 对应收款金额数组（按持份比例分配，总和=凭证面值）
     */
    function autoSettle(
        uint256 tokenId,
        address[] calldata payees,
        uint256[] calldata payAmounts
    ) external onlyAdmin returns (bool) {
        require(payees.length == payAmounts.length, "RWA_Settlement: payees and amounts length mismatch");
        require(payees.length > 0, "RWA_Settlement: empty payees");

        // 通过成员1 getAssetInfo 查询资产信息（对齐实际接口）
        (, address coreEnterprise, , uint256 maturityDate, , , , uint256 totalValue, ) =
            assetCore.getAssetInfo(tokenId);

        // 校验已到期
        require(block.timestamp >= maturityDate, "RWA_Settlement: token not yet matured");

        // 校验处于可清算状态
        Status currentStatus = tokenStatus[tokenId];
        require(
            currentStatus == Status.Circulating ||
            currentStatus == Status.Pledged ||
            currentStatus == Status.Overdue,
            "RWA_Settlement: token not in settleable status"
        );

        // 校验总兑付金额等于凭证面值
        uint256 totalPayAmount;
        for (uint256 i = 0; i < payAmounts.length; i++) {
            totalPayAmount += payAmounts[i];
        }
        require(totalPayAmount == totalValue, "RWA_Settlement: total pay amount must equal token value");

        // ===== 调用成员1 settleAsset 标记底层资产为已结算 =====
        // 需本合约被授予 FINANCIAL_INSTITUTION_ROLE
        assetCore.settleAsset(tokenId);

        // ===== 如果有活跃质押，标记为已结清 =====
        uint256 activePledgeId = activePledgeOf[tokenId];
        if (activePledgeId != 0) {
            pledgeRecords[activePledgeId].isRepaid = true;
            pledgeRecords[activePledgeId].repayTimestamp = block.timestamp;
            activePledgeOf[tokenId] = 0;
        }

        // ===== 记录清算信息（链下银行据此执行资金划转） =====
        address[] memory payeesCopy = new address[](payees.length);
        uint256[] memory amountsCopy = new uint256[](payAmounts.length);
        for (uint256 i = 0; i < payees.length; i++) {
            payeesCopy[i] = payees[i];
            amountsCopy[i] = payAmounts[i];
        }

        settlementRecords[tokenId] = SettlementRecord({
            tokenId: tokenId,
            maturityTimestamp: maturityDate,
            settleTimestamp: block.timestamp,
            totalValue: totalValue,
            coreEnterprise: coreEnterprise,
            payees: payeesCopy,
            payAmounts: amountsCopy,
            isCompleted: true
        });

        // 状态变更：→ Settled
        _changeStatus(tokenId, Status.Settled, "Maturity settlement completed");

        // 触发清算事件（链下银行系统监听，执行实际资金划转）
        emit AssetSettled(tokenId, totalValue, coreEnterprise, block.timestamp, payeesCopy, amountsCopy);

        return true;
    }

    /**
     * @dev 标记凭证逾期（到期未兑付时由管理员触发）
     */
    function markOverdue(uint256 tokenId) external onlyAdmin {
        (, , , uint256 maturityDate, , , , , ) = assetCore.getAssetInfo(tokenId);
        require(block.timestamp > maturityDate, "RWA_Settlement: token not yet overdue");

        Status currentStatus = tokenStatus[tokenId];
        require(
            currentStatus == Status.Circulating || currentStatus == Status.Pledged,
            "RWA_Settlement: token not in overdue-able status"
        );

        _changeStatus(tokenId, Status.Overdue, "Maturity passed without settlement");
        emit AssetOverdue(tokenId, maturityDate, block.timestamp);
    }

    // ========== 查询函数 ==========

    function getTokenStatus(uint256 tokenId) external view returns (Status) {
        return tokenStatus[tokenId];
    }

    function getStatusHistory(uint256 tokenId) external view returns (StatusChange[] memory) {
        return statusHistory[tokenId];
    }

    function getActivePledge(uint256 tokenId) external view returns (PledgeRecord memory) {
        uint256 pledgeId = activePledgeOf[tokenId];
        require(pledgeId != 0, "RWA_Settlement: no active pledge for this token");
        return pledgeRecords[pledgeId];
    }

    function getPledgesByBorrower(address borrower) external view returns (PledgeRecord[] memory) {
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

    function getSettlementRecord(uint256 tokenId) external view returns (SettlementRecord memory) {
        return settlementRecords[tokenId];
    }

    /**
     * @dev 查询即将到期的凭证（供后端定时任务扫描）
     */
    function checkMaturedTokens(uint256[] calldata tokenIds) external view returns (uint256[] memory maturedTokens) {
        uint256 count;
        for (uint256 i = 0; i < tokenIds.length; i++) {
            (, , , uint256 maturityDate, , , , , ) = assetCore.getAssetInfo(tokenIds[i]);
            if (block.timestamp >= maturityDate && tokenStatus[tokenIds[i]] != Status.Settled) {
                count++;
            }
        }
        maturedTokens = new uint256[](count);
        uint256 index;
        for (uint256 i = 0; i < tokenIds.length; i++) {
            (, , , uint256 maturityDate, , , , , ) = assetCore.getAssetInfo(tokenIds[i]);
            if (block.timestamp >= maturityDate && tokenStatus[tokenIds[i]] != Status.Settled) {
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
