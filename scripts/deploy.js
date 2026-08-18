 // scripts/deploy.js
 // RWA 平台合约部署脚本 — 部署 RWA_Core_Asset 确权主合约
 //
 // 用法:
 //   npx hardhat run scripts/deploy.js --network localhost
 //   npx hardhat run scripts/deploy.js --network hardhat  (本地模拟)
 
 const hre = require("hardhat");
 
 async function main() {
   console.log("═══════════════════════════════════════════════");
   console.log("  RWA 平台 — 合约部署脚本");
   console.log("═══════════════════════════════════════════════\n");
 
   const [deployer, byd, kedali, juyong, changyuan, ccb] = await hre.ethers.getSigners();
 
   console.log(`部署账户: ${deployer.address}`);
   console.log(`核心企业 (比亚迪):        ${byd.address}`);
   console.log(`一级供应商 (科达利):      ${kedali.address}`);
   console.log(`二级供应商 (聚能永拓):    ${juyong.address}`);
   console.log(`三级供应商 (长园特发):    ${changyuan.address}`);
   console.log(`金融机构 (建设银行):      ${ccb.address}\n`);
 
   // ─── 部署 RWA_Core_Asset 确权合约 ───
   const RWA_Core_Asset = await hre.ethers.getContractFactory("RWA_Core_Asset");
   const coreAsset = await RWA_Core_Asset.deploy(
     "RWA Supply Chain Asset",  // name
     "RWASCA"                   // symbol
   );
   await coreAsset.waitForDeployment();
 
   const coreAssetAddress = await coreAsset.getAddress();
   console.log(`✅ RWA_Core_Asset 已部署至: ${coreAssetAddress}\n`);
 
   // ─── 配置角色权限（基于比亚迪融资场景） ───
   const CORE_ENTERPRISE_ROLE = await coreAsset.CORE_ENTERPRISE_ROLE();
   const SUPPLIER_ROLE = await coreAsset.SUPPLIER_ROLE();
   const FINANCIAL_INSTITUTION_ROLE = await coreAsset.FINANCIAL_INSTITUTION_ROLE();
   const AUDITOR_ROLE = await coreAsset.AUDITOR_ROLE();
 
   const roles = [
     { addr: byd.address,    role: CORE_ENTERPRISE_ROLE,      label: "核心企业: 比亚迪" },
     { addr: kedali.address, role: SUPPLIER_ROLE,             label: "一级供应商: 科达利" },
     { addr: juyong.address, role: SUPPLIER_ROLE,             label: "二级供应商: 聚能永拓" },
     { addr: changyuan.address, role: SUPPLIER_ROLE,          label: "三级供应商: 长园特发" },
     { addr: ccb.address,    role: FINANCIAL_INSTITUTION_ROLE, label: "金融机构: 建设银行" },
   ];
 
   for (const r of roles) {
     const tx = await coreAsset.grantRole(r.role, r.addr);
     await tx.wait();
     console.log(`  🔑 已授予 ${r.label} (${r.addr})`);
   }
   console.log("");
 
   // ─── 签发一笔演示 RWA 凭证（比亚迪 -> 科达利） ───
   const slot = await coreAsset.computeSlot(
     byd.address,
     Math.floor(Date.now() / 1000) + 180 * 24 * 3600,  // 6 个月后到期
     1  // 采购合同
   );
 
   const faceValue = 10_000_000_00n; // 1000 万元（以分为单位）
   const maturityDate = Math.floor(Date.now() / 1000) + 180 * 24 * 3600;
   const contractHash = ethers.keccak256(ethers.toUtf8Bytes("BYD-Kedali-2026-PowerBattery-001"));
   const metadataURI = "https://rwa-platform.example/assets/meta/001.json";
 
   const mintTx = await coreAsset.connect(byd).mintRWAAsset(
     kedali.address,
     slot,
     faceValue,
     maturityDate,
     contractHash,
     metadataURI
   );
   const receipt = await mintTx.wait();
 
   // 解析事件获取 tokenId
   const assetCreatedEvent = receipt.logs
     .map((log) => {
       try { return coreAsset.interface.parseLog(log); } catch { return null; }
     })
     .find((e) => e && e.name === "AssetCreated");
 
   const tokenId = assetCreatedEvent ? assetCreatedEvent.args.tokenId : "N/A";
   console.log(`📄 演示凭证已签发:`);
   console.log(`   Token ID:   ${tokenId}`);
   console.log(`   签发方:     比亚迪`);
   console.log(`   接收方:     科达利精密工业`);
   console.log(`   面值:       10,000,000 元`);
   console.log(`   到期日:     6 个月后`);
   console.log(`   合同哈希:   ${contractHash}\n`);
 
   // ─── 打印部署摘要 ───
   console.log("═══════════════════════════════════════════════");
   console.log("  📋 部署摘要");
   console.log("═══════════════════════════════════════════════");
   console.log(`  RWA_Core_Asset     ${coreAssetAddress}`);
   console.log(`  网络:              ${hre.network.name}`);
   console.log(`  区块链:            ${hre.network.config.chainId}`);
   console.log("═══════════════════════════════════════════════\n");
 }
 
 main()
   .then(() => process.exit(0))
   .catch((error) => {
     console.error("❌ 部署失败:", error);
     process.exit(1);
   });
