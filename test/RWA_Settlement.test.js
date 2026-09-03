// test/RWA_Settlement.test.js
// 成员2 - RWA_Settlement 合约单元测试（对齐成员1实际接口）

const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("RWA_Settlement 贴现质押与到期清算合约", function () {
  let mockAssetCore;
  let settlement;
  let owner, supplier1, supplier2, coreEnterprise, bank;

  const SLOT_1 = 1735689600;
  const TOKEN_VALUE = 1000000;
  const FUTURE_MATURITY = Math.floor(Date.now() / 1000) + 86400 * 30;
  const PAST_MATURITY = Math.floor(Date.now() / 1000) - 86400;

  beforeEach(async function () {
    [owner, supplier1, supplier2, coreEnterprise, bank] = await ethers.getSigners();

    const MockAssetCore = await ethers.getContractFactory("MockAssetCore");
    mockAssetCore = await MockAssetCore.deploy();

    const Settlement = await ethers.getContractFactory("RWA_Settlement");
    settlement = await Settlement.deploy(await mockAssetCore.getAddress());

    // 设置角色
    await mockAssetCore.setRole(await mockAssetCore.SUPPLIER_ROLE(), supplier1.address, true);
    await mockAssetCore.setRole(await mockAssetCore.SUPPLIER_ROLE(), supplier2.address, true);
    await mockAssetCore.setRole(await mockAssetCore.CORE_ENTERPRISE_ROLE(), coreEnterprise.address, true);
    await mockAssetCore.setRole(await mockAssetCore.FINANCIAL_INSTITUTION_ROLE(), bank.address, true);
    // 给settlement合约授予金融机构角色（才能调用settleAsset）
    await mockAssetCore.setRole(await mockAssetCore.FINANCIAL_INSTITUTION_ROLE(), await settlement.getAddress(), true);
  });

  async function createToken(ownerAddr, maturity) {
    await mockAssetCore.createTestToken(ownerAddr, SLOT_1, TOKEN_VALUE, maturity, coreEnterprise.address);
  }

  // 辅助：授权NFT转移给settlement合约（pledgeForLoan前置条件）
  async function approveNFTForSettlement(tokenId, ownerSigner) {
    await mockAssetCore.connect(ownerSigner)["approve(address,uint256)"](await settlement.getAddress(), tokenId);
  }

  describe("状态机流转", function () {
    it("应该从未流通转为流通中", async function () {
      await createToken(supplier1.address, FUTURE_MATURITY);
      await expect(
        settlement.connect(supplier1).markAsCirculating(1)
      ).to.emit(settlement, "StatusChanged");
      expect(await settlement.getTokenStatus(1)).to.equal(1);
    });

    it("应该拒绝非法状态流转（已兑付不可再变更）", async function () {
      await createToken(supplier1.address, PAST_MATURITY);
      await settlement.connect(supplier1).markAsCirculating(1);

      // 到期兑付（调用settleAsset）
      await settlement.connect(owner).autoSettle(1, [supplier1.address], [TOKEN_VALUE]);

      // 已兑付后再尝试变更状态应该失败
      await expect(
        settlement.connect(supplier1).markAsCirculating(1)
      ).to.be.revertedWith("RWA_Settlement: token already settled, cannot change status");
    });
  });

  describe("pledgeForLoan 质押融资（真正托管，修复审查问题#3）", function () {
    beforeEach(async function () {
      await createToken(supplier1.address, FUTURE_MATURITY);
      await settlement.connect(supplier1).markAsCirculating(1);
    });

    it("应该成功质押、触发FinancingApplied事件、并将凭证NFT转移到合约托管", async function () {
      const loanAmount = 700000;
      const interestRate = 500;
      const repayDate = FUTURE_MATURITY - 86400;

      // 前置：授权NFT转移
      await approveNFTForSettlement(1, supplier1);

      await expect(
        settlement.connect(supplier1).pledgeForLoan(1, bank.address, loanAmount, interestRate, repayDate)
      ).to.emit(settlement, "FinancingApplied");

      // 验证状态变为Pledged
      expect(await settlement.getTokenStatus(1)).to.equal(2);

      // 验证凭证NFT已转移到settlement合约托管（修复审查问题#3的核心）
      expect(await mockAssetCore.ownerOf(1)).to.equal(await settlement.getAddress());

      // 验证质押记录
      const record = await settlement.pledgeRecords(1);
      expect(record.borrower).to.equal(supplier1.address);
      expect(record.bank).to.equal(bank.address);
      expect(record.loanAmount).to.equal(loanAmount);
      expect(record.isRepaid).to.equal(false);
    });

    it("应该拒绝非流通状态的凭证质押", async function () {
      await createToken(supplier2.address, FUTURE_MATURITY);
      await approveNFTForSettlement(2, supplier2);
      // 未流通状态直接质押
      await expect(
        settlement.connect(supplier2).pledgeForLoan(2, bank.address, 500000, 500, FUTURE_MATURITY - 86400)
      ).to.be.revertedWith("RWA_Settlement: token must be in Circulating status to pledge");
    });

    it("应该拒绝融资金额超过凭证面值", async function () {
      await approveNFTForSettlement(1, supplier1);
      await expect(
        settlement.connect(supplier1).pledgeForLoan(1, bank.address, TOKEN_VALUE + 1, 500, FUTURE_MATURITY - 86400)
      ).to.be.revertedWith("RWA_Settlement: loan amount exceeds token value");
    });

    it("应该拒绝向非金融机构地址质押", async function () {
      await approveNFTForSettlement(1, supplier1);
      await expect(
        settlement.connect(supplier1).pledgeForLoan(1, supplier2.address, 500000, 500, FUTURE_MATURITY - 86400)
      ).to.be.revertedWith("RWA_Settlement: target is not a financial institution");
    });

    it("应该拒绝未授权NFT转移的质押（无approve则失败）", async function () {
      // 不调用approveNFTForSettlement
      await expect(
        settlement.connect(supplier1).pledgeForLoan(1, bank.address, 500000, 500, FUTURE_MATURITY - 86400)
      ).to.be.revertedWith("RWA: not authorized");
    });
  });

  describe("repayAndRelease 还款解押", function () {
    beforeEach(async function () {
      await createToken(supplier1.address, FUTURE_MATURITY);
      await settlement.connect(supplier1).markAsCirculating(1);
      await approveNFTForSettlement(1, supplier1);
      await settlement.connect(supplier1).pledgeForLoan(1, bank.address, 700000, 500, FUTURE_MATURITY - 86400);
    });

    it("应该成功还款解押、恢复流通状态、并将凭证NFT转回借款人", async function () {
      await expect(
        settlement.connect(supplier1).repayAndRelease(1)
      ).to.emit(settlement, "LoanRepaid");

      // 状态恢复Circulating
      expect(await settlement.getTokenStatus(1)).to.equal(1);

      // 凭证NFT转回借款人（修复审查问题#3的解押部分）
      expect(await mockAssetCore.ownerOf(1)).to.equal(supplier1.address);

      const record = await settlement.pledgeRecords(1);
      expect(record.isRepaid).to.equal(true);
    });

    it("应该拒绝非借款人还款", async function () {
      await expect(
        settlement.connect(supplier2).repayAndRelease(1)
      ).to.be.revertedWith("RWA_Settlement: caller is not borrower");
    });
  });

  describe("autoSettle 到期兑付（链上状态+链下资金，修复审查问题#4）", function () {
    beforeEach(async function () {
      await createToken(supplier1.address, PAST_MATURITY);
      await settlement.connect(supplier1).markAsCirculating(1);
    });

    it("应该成功到期兑付、调用settleAsset、并触发AssetSettled事件", async function () {
      await expect(
        settlement.connect(owner).autoSettle(1, [supplier1.address], [TOKEN_VALUE])
      ).to.emit(settlement, "AssetSettled");

      expect(await settlement.getTokenStatus(1)).to.equal(3); // Settled

      // 验证成员1底层资产状态也变为Settled（status=3）
      const assetInfo = await mockAssetCore.getAssetInfo(1);
      expect(assetInfo[4]).to.equal(3);

      // 验证清算记录
      const record = await settlement.getSettlementRecord(1);
      expect(record.isCompleted).to.equal(true);
      expect(record.totalValue).to.equal(TOKEN_VALUE);
    });

    it("应该拒绝未到期凭证兑付", async function () {
      await createToken(supplier2.address, FUTURE_MATURITY);
      await settlement.connect(supplier2).markAsCirculating(2);

      await expect(
        settlement.connect(owner).autoSettle(2, [supplier2.address], [TOKEN_VALUE])
      ).to.be.revertedWith("RWA_Settlement: token not yet matured");
    });

    it("应该拒绝总兑付金额不等于凭证面值", async function () {
      await expect(
        settlement.connect(owner).autoSettle(1, [supplier1.address], [TOKEN_VALUE - 1])
      ).to.be.revertedWith("RWA_Settlement: total pay amount must equal token value");
    });

    it("应该按持份比例向多方兑付（收款方列表写入链上事件供链下银行执行）", async function () {
      const payees = [supplier1.address, bank.address];
      const amounts = [600000, 400000];

      await expect(
        settlement.connect(owner).autoSettle(1, payees, amounts)
      ).to.emit(settlement, "AssetSettled");

      const record = await settlement.getSettlementRecord(1);
      expect(record.isCompleted).to.equal(true);
      expect(record.totalValue).to.equal(TOKEN_VALUE);
      expect(record.payees.length).to.equal(2);
      expect(record.payAmounts[0]).to.equal(600000);
      expect(record.payAmounts[1]).to.equal(400000);
    });

    it("质押中的凭证到期兑付后应该自动结清质押记录", async function () {
      // 先质押（凭证已到期，但expectedRepayDate设为未来）
      await approveNFTForSettlement(1, supplier1);
      await settlement.connect(supplier1).pledgeForLoan(1, bank.address, 700000, 500, FUTURE_MATURITY);

      // 到期兑付（凭证在settlement合约托管中）
      await settlement.connect(owner).autoSettle(1, [bank.address, supplier1.address], [700000, 300000]);

      // 质押记录应被标记为已结清
      const record = await settlement.pledgeRecords(1);
      expect(record.isRepaid).to.equal(true);
      expect(await settlement.getTokenStatus(1)).to.equal(3);
    });
  });

  describe("markOverdue 逾期标记", function () {
    it("应该成功标记逾期", async function () {
      await createToken(supplier1.address, PAST_MATURITY);
      await settlement.connect(supplier1).markAsCirculating(1);

      await expect(
        settlement.connect(owner).markOverdue(1)
      ).to.emit(settlement, "AssetOverdue");

      expect(await settlement.getTokenStatus(1)).to.equal(4);
    });

    it("应该拒绝未到期凭证标记逾期", async function () {
      await createToken(supplier1.address, FUTURE_MATURITY);
      await settlement.connect(supplier1).markAsCirculating(1);

      await expect(
        settlement.connect(owner).markOverdue(1)
      ).to.be.revertedWith("RWA_Settlement: token not yet overdue");
    });
  });

  describe("查询函数", function () {
    it("应该正确查询状态变更历史", async function () {
      await createToken(supplier1.address, FUTURE_MATURITY);
      await settlement.connect(supplier1).markAsCirculating(1);

      const history = await settlement.getStatusHistory(1);
      expect(history.length).to.equal(1);
      expect(history[0].fromStatus).to.equal(0);
      expect(history[0].toStatus).to.equal(1);
    });
  });
});
