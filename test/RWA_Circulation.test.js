// test/RWA_Circulation.test.js
// 成员2 - RWA_Circulation 合约单元测试（对齐成员1实际接口）

const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("RWA_Circulation 凭证无损拆分与流转合约", function () {
  let mockAssetCore;
  let settlement;
  let circulation;
  let owner, supplier1, supplier2, supplier3, coreEnterprise, bank;

  const SLOT_1 = 1735689600;
  const TOKEN_VALUE = 1000000;
  const FUTURE_MATURITY = Math.floor(Date.now() / 1000) + 86400 * 30;

  beforeEach(async function () {
    [owner, supplier1, supplier2, supplier3, coreEnterprise, bank] = await ethers.getSigners();

    // 部署Mock合约（对齐成员1实际接口）
    const MockAssetCore = await ethers.getContractFactory("MockAssetCore");
    mockAssetCore = await MockAssetCore.deploy();

    // 部署Settlement（Circulation依赖其地址做质押保护检查）
    const Settlement = await ethers.getContractFactory("RWA_Settlement");
    settlement = await Settlement.deploy(await mockAssetCore.getAddress());

    // 部署Circulation
    const Circulation = await ethers.getContractFactory("RWA_Circulation");
    circulation = await Circulation.deploy(
      await mockAssetCore.getAddress(),
      await settlement.getAddress()
    );

    // 设置角色
    await mockAssetCore.setRole(await mockAssetCore.SUPPLIER_ROLE(), supplier1.address, true);
    await mockAssetCore.setRole(await mockAssetCore.SUPPLIER_ROLE(), supplier2.address, true);
    await mockAssetCore.setRole(await mockAssetCore.SUPPLIER_ROLE(), supplier3.address, true);
    await mockAssetCore.setRole(await mockAssetCore.CORE_ENTERPRISE_ROLE(), coreEnterprise.address, true);
    await mockAssetCore.setRole(await mockAssetCore.FINANCIAL_INSTITUTION_ROLE(), bank.address, true);

    // 创建测试凭证：createTestToken(owner, slot, value, maturityDate, issuer)
    await mockAssetCore.createTestToken(supplier1.address, SLOT_1, TOKEN_VALUE, FUTURE_MATURITY, coreEnterprise.address);
    await mockAssetCore.createTestToken(supplier2.address, SLOT_1, 0, FUTURE_MATURITY, coreEnterprise.address);
    await mockAssetCore.createTestToken(supplier3.address, SLOT_1, 0, FUTURE_MATURITY, coreEnterprise.address);
  });

  // 辅助：授权价值给Circulation合约（ERC-3525 transferFrom前置条件）
  async function approveValue(tokenId, spender, value) {
    await mockAssetCore.connect(await getTokenOwner(tokenId)).approve(tokenId, spender, value);
  }

  async function getTokenOwner(tokenId) {
    const ownerAddr = await mockAssetCore.ownerOf(tokenId);
    const signers = await ethers.getSigners();
    for (const s of signers) {
      if (s.address.toLowerCase() === ownerAddr.toLowerCase()) return s;
    }
    return signers[0];
  }

  describe("splitAndTransfer 凭证拆分（3参数 ERC-3525标准）", function () {
    it("应该成功拆分凭证并保证金额守恒", async function () {
      const splitValue = 400000;
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), splitValue);

      await expect(
        circulation.connect(supplier1).splitAndTransfer(1, 2, splitValue)
      ).to.emit(circulation, "AssetSplit");

      expect(await mockAssetCore.balanceOf(1)).to.equal(TOKEN_VALUE - splitValue);
      expect(await mockAssetCore.balanceOf(2)).to.equal(splitValue);

      const record = await circulation.splitRecords(1);
      expect(record.splitValue).to.equal(splitValue);
      expect(record.remainingValue).to.equal(TOKEN_VALUE - splitValue);
      expect(record.splitter).to.equal(supplier1.address);
      expect(record.recipient).to.equal(supplier2.address);
    });

    it("应该拒绝非凭证持有者的拆分操作", async function () {
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 100000);
      await expect(
        circulation.connect(supplier2).splitAndTransfer(1, 2, 100000)
      ).to.be.revertedWith("RWA_Circulation: caller is not the token owner");
    });

    it("应该拒绝拆分金额超过凭证余额", async function () {
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), TOKEN_VALUE + 1);
      await expect(
        circulation.connect(supplier1).splitAndTransfer(1, 2, TOKEN_VALUE + 1)
      ).to.be.revertedWith("RWA_Circulation: insufficient token balance");
    });

    it("应该拒绝拆分金额为0", async function () {
      await expect(
        circulation.connect(supplier1).splitAndTransfer(1, 2, 0)
      ).to.be.revertedWith("RWA_Circulation: split value must be positive");
    });

    it("应该拒绝向非供应商持有者的凭证拆分", async function () {
      // 创建一个owner不是供应商的凭证
      await mockAssetCore.createTestToken(owner.address, SLOT_1, 0, FUTURE_MATURITY, coreEnterprise.address);
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 100000);

      await expect(
        circulation.connect(supplier1).splitAndTransfer(1, 4, 100000)
      ).to.be.revertedWith("RWA_Circulation: recipient is not a supplier");
    });

    it("应该拒绝Slot不一致的凭证拆分（到期日属性继承校验）", async function () {
      await mockAssetCore.createTestToken(supplier2.address, SLOT_1 + 1, 0, FUTURE_MATURITY + 86400, coreEnterprise.address);
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 100000);

      await expect(
        circulation.connect(supplier1).splitAndTransfer(1, 4, 100000)
      ).to.be.revertedWith("RWA_Circulation: slot mismatch - tokens must share same maturity");
    });

    it("应该支持多级拆分（一级→二级→三级，信用穿透）", async function () {
      // 一级拆分
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 600000);
      await circulation.connect(supplier1).splitAndTransfer(1, 2, 600000);

      // 二级拆分
      await mockAssetCore.connect(supplier2)["approve(uint256,address,uint256)"](2, await circulation.getAddress(), 300000);
      await circulation.connect(supplier2).splitAndTransfer(2, 3, 300000);

      expect(await mockAssetCore.balanceOf(1)).to.equal(400000);
      expect(await mockAssetCore.balanceOf(2)).to.equal(300000);
      expect(await mockAssetCore.balanceOf(3)).to.equal(300000);

      expect(await circulation.splitLevelOf(2)).to.equal(1);
      expect(await circulation.splitLevelOf(3)).to.equal(2);

      const lineage = await circulation.getSplitLineage(3);
      expect(lineage.length).to.equal(3);
      expect(lineage[0]).to.equal(1);
      expect(lineage[1]).to.equal(2);
      expect(lineage[2]).to.equal(3);
    });

    it("应该拒绝拆分到同一个凭证", async function () {
      await expect(
        circulation.connect(supplier1).splitAndTransfer(1, 1, 100000)
      ).to.be.revertedWith("RWA_Circulation: cannot split to same token");
    });

    it("守恒校验应该使用增量法：toTokenId有初始余额时仍正确校验（修复审查问题#2）", async function () {
      // 给token2预先存入200000余额
      await mockAssetCore.createTestToken(supplier2.address, SLOT_1, 200000, FUTURE_MATURITY, coreEnterprise.address);
      const tokenIdWithBalance = 4;

      const splitValue = 300000;
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), splitValue);

      await circulation.connect(supplier1).splitAndTransfer(1, tokenIdWithBalance, splitValue);

      // 增量校验：token4 从200000增加到500000，增量=300000
      expect(await mockAssetCore.balanceOf(tokenIdWithBalance)).to.equal(500000);
      expect(await mockAssetCore.balanceOf(1)).to.equal(TOKEN_VALUE - splitValue);
    });

    it("应该拒绝已质押托管的凭证拆分（修复审查问题#3）", async function () {
      // 模拟凭证已被质押：创建一个owner为settlement合约的凭证（质押后凭证托管在settlement合约）
      await mockAssetCore.createTestToken(await settlement.getAddress(), SLOT_1, TOKEN_VALUE, FUTURE_MATURITY, coreEnterprise.address);
      const pledgedTokenId = 4;

      // supplier1不是该凭证owner，尝试拆分应被拒绝
      await expect(
        circulation.connect(supplier1).splitAndTransfer(pledgedTokenId, 2, 100000)
      ).to.be.revertedWith("RWA_Circulation: caller is not the token owner");
    });
  });

  describe("batchSplitAndTransfer 批量拆分", function () {
    it("应该成功批量拆分为多张子凭证", async function () {
      const toTokenIds = [2, 3];
      const values = [400000, 300000];
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 700000);

      await circulation.connect(supplier1).batchSplitAndTransfer(1, toTokenIds, values);

      expect(await mockAssetCore.balanceOf(1)).to.equal(300000);
      expect(await mockAssetCore.balanceOf(2)).to.equal(400000);
      expect(await mockAssetCore.balanceOf(3)).to.equal(300000);
    });

    it("应该拒绝总拆分金额超过凭证余额", async function () {
      const toTokenIds = [2, 3];
      const values = [600000, 500000];
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 1100000);

      await expect(
        circulation.connect(supplier1).batchSplitAndTransfer(1, toTokenIds, values)
      ).to.be.revertedWith("RWA_Circulation: total split value exceeds balance");
    });

    it("应该拒绝数组长度不一致", async function () {
      const toTokenIds = [2, 3];
      const values = [400000];

      await expect(
        circulation.connect(supplier1).batchSplitAndTransfer(1, toTokenIds, values)
      ).to.be.revertedWith("RWA_Circulation: array length mismatch");
    });
  });

  describe("查询函数", function () {
    it("应该正确判断子凭证", async function () {
      expect(await circulation.isSplitChild(1)).to.equal(false);
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 400000);
      await circulation.connect(supplier1).splitAndTransfer(1, 2, 400000);
      expect(await circulation.isSplitChild(2)).to.equal(true);
    });

    it("应该正确查询拆分溯源链路", async function () {
      await mockAssetCore.connect(supplier1)["approve(uint256,address,uint256)"](1, await circulation.getAddress(), 400000);
      await circulation.connect(supplier1).splitAndTransfer(1, 2, 400000);
      const lineage = await circulation.getSplitLineage(2);
      expect(lineage.length).to.equal(2);
      expect(lineage[0]).to.equal(1);
      expect(lineage[1]).to.equal(2);
    });
  });
});
