// scripts/deploy.js
// 成员2合约部署脚本（对齐成员1 RWA_Core_Asset 实际接口）
// 部署顺序：先部署 RWA_Settlement，再部署 RWA_Circulation（Circulation依赖Settlement地址做质押保护）

const hre = require("hardhat");

async function main() {
  console.log("========================================");
  console.log("  供应链RWA - 成员2合约部署");
  console.log("  金融业务逻辑与流转合约");
  console.log("========================================\n");

  // ========== 配置成员1合约地址 ==========
  const ASSET_CORE_ADDRESS = process.env.ASSET_CORE_ADDRESS || "0x5FbDB2315678afecb367f032d93F642f64180aa3";
  console.log(`成员1 RWA_Core_Asset 地址: ${ASSET_CORE_ADDRESS}`);
  console.log();

  // ========== 第一步：部署 RWA_Settlement ==========
  console.log("[1/2] 正在部署 RWA_Settlement.sol（贴现质押与到期清算合约）...");
  const Settlement = await hre.ethers.getContractFactory("RWA_Settlement");
  const settlement = await Settlement.deploy(ASSET_CORE_ADDRESS);
  await settlement.waitForDeployment();
  const settlementAddress = await settlement.getAddress();
  console.log(`  ✓ RWA_Settlement 部署成功: ${settlementAddress}`);
  console.log();

  // ========== 第二步：部署 RWA_Circulation ==========
  console.log("[2/2] 正在部署 RWA_Circulation.sol（凭证无损拆分与流转合约）...");
  const Circulation = await hre.ethers.getContractFactory("RWA_Circulation");
  const circulation = await Circulation.deploy(ASSET_CORE_ADDRESS, settlementAddress);
  await circulation.waitForDeployment();
  const circulationAddress = await circulation.getAddress();
  console.log(`  ✓ RWA_Circulation 部署成功: ${circulationAddress}`);
  console.log();

  // ========== 部署后配置提示 ==========
  console.log("========================================");
  console.log("  部署完成！合约地址汇总：");
  console.log("========================================");
  console.log(`  RWA_Settlement:   ${settlementAddress}`);
  console.log(`  RWA_Circulation:  ${circulationAddress}`);
  console.log();
  console.log("【重要】部署后需要成员1配合：");
  console.log("  1. 给 RWA_Settlement 合约授予 FINANCIAL_INSTITUTION_ROLE");
  console.log("     （才能调用 settleAsset 执行到期清算）");
  console.log("  2. 确认 RWA_Circulation 和 RWA_Settlement 的调用权限");
  console.log();
  console.log("【使用前置条件】用户调用业务函数前需授权：");
  console.log("  - 拆分凭证: assetCore.approve(tokenId, circulationAddress, value)");
  console.log("  - 质押融资: assetCore.approve(settlementAddress, tokenId) [NFT授权]");
  console.log();
  console.log("请将以上地址同步给成员3（后端API开发）和成员1（链底座配置）");
  console.log("========================================");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
