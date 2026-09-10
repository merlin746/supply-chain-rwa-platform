// 绕过后端直接在链上拆分，验证 EventSyncService 的补缺能力（评审项1回归测试）
const hre = require("hardhat");

async function main() {
  const signers = await hre.ethers.getSigners();
  const kedali = signers[2];      // 科达利（token 1 持有方）
  const changyuan = signers[4];   // 长园特发（接收方）

  const coreAsset = await hre.ethers.getContractAt(
    "RWA_Core_Asset",
    "0x5FbDB2315678afecb367f032d93F642f64180aa3"
  );

  // 拆分 100 万（链上单位：分）
  const value = 1_000_000_00n;
  const tx = await coreAsset.connect(kedali)["transferFrom(uint256,address,uint256)"](
    1, changyuan.address, value
  );
  const receipt = await tx.wait();

  const ev = receipt.logs
    .map((l) => { try { return coreAsset.interface.parseLog(l); } catch { return null; } })
    .find((e) => e && e.name === "AssetSplit");

  console.log("链上拆分完成（绕过后端）:");
  console.log("  parent:", ev.args.parentTokenId.toString());
  console.log("  child:", ev.args.childTokenId.toString());
  console.log("  value:", ev.args.value.toString(), "分");
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
