 const { expect } = require("chai");
 const { ethers } = require("hardhat");
 
 describe("RWA_Core_Asset -- Core Asset Confirmation Contract", function () {
   let coreAsset, deployer, byd, kedali, juyong, changyuan, ccb;
   const ZERO = "0x0000000000000000000000000000000000000000";
 
   // helper: call overloaded functions via qualified signatures
   const $ = (ctr, sig) => ctr.getFunction(sig);
 
   before(async function () {
     [deployer, byd, kedali, juyong, changyuan, ccb] = await ethers.getSigners();
     const Factory = await ethers.getContractFactory("RWA_Core_Asset");
     coreAsset = await Factory.deploy("RWA Supply Chain Asset", "RWASCA");
     await coreAsset.waitForDeployment();
 
     const CORE = await coreAsset.CORE_ENTERPRISE_ROLE();
     const SUP  = await coreAsset.SUPPLIER_ROLE();
     const FIN  = await coreAsset.FINANCIAL_INSTITUTION_ROLE();
     await coreAsset.grantRole(CORE, byd.address);
     await coreAsset.grantRole(SUP,  kedali.address);
     await coreAsset.grantRole(SUP,  juyong.address);
     await coreAsset.grantRole(SUP,  changyuan.address);
     await coreAsset.grantRole(FIN,  ccb.address);
   });
 
   // ═══════════════════════════════════════
   //  1. Contract initialization & metadata
   // ═══════════════════════════════════════
   describe("1. Contract init", function () {
     it("sets name and symbol", async function () {
       expect(await coreAsset.name()).to.equal("RWA Supply Chain Asset");
       expect(await coreAsset.symbol()).to.equal("RWASCA");
     });
     it("deployer has DEFAULT_ADMIN_ROLE", async function () {
       const ADMIN = await coreAsset.DEFAULT_ADMIN_ROLE();
       expect(await coreAsset.hasRole(ADMIN, deployer.address)).to.be.true;
     });
     it("totalSupply is 0 initially", async function () {
       expect(await coreAsset.totalSupply()).to.equal(0n);
     });
   });
 
   // ═══════════════════════════════════════
   //  2. RWA token minting
   // ═══════════════════════════════════════
   describe("2. mintRWAAsset", function () {
     const future = Math.floor(Date.now() / 1000) + 180 * 86400;
     const VAL = 10_000_000_00n;
     const HASH = ethers.keccak256(ethers.toUtf8Bytes("TEST-001"));
     let slot;
 
     it("core enterprise can mint", async function () {
       slot = await coreAsset.computeSlot(byd.address, future, 1);
       const tx = await coreAsset.connect(byd).mintRWAAsset(
         kedali.address, slot, VAL, future, HASH, "https://example.com/meta/1.json"
       );
       await expect(tx).to.emit(coreAsset, "AssetCreated")
         .withArgs(1n, byd.address, slot, VAL, BigInt(future), HASH, "https://example.com/meta/1.json");
       expect(await coreAsset.totalSupply()).to.equal(1n);
     });
     it("non-core enterprise cannot mint", async function () {
       await expect(
         coreAsset.connect(kedali).mintRWAAsset(kedali.address, slot, VAL, future, HASH, "")
       ).to.be.reverted;
     });
     it("mint to zero address fails", async function () {
       await expect(
         coreAsset.connect(byd).mintRWAAsset(ZERO, slot, VAL, future, HASH, "")
       ).to.be.revertedWith("RWA: mint to zero address");
     });
     it("mint zero value fails", async function () {
       await expect(
         coreAsset.connect(byd).mintRWAAsset(kedali.address, slot, 0, future, HASH, "")
       ).to.be.revertedWith("RWA: mint value must be > 0");
     });
     it("maturity must be in future", async function () {
       const past = Math.floor(Date.now() / 1000) - 3600;
       await expect(
         coreAsset.connect(byd).mintRWAAsset(kedali.address, slot, VAL, past, HASH, "")
       ).to.be.revertedWith("RWA: maturity must be in future");
     });
     it("contract hash cannot be empty", async function () {
       await expect(
         coreAsset.connect(byd).mintRWAAsset(kedali.address, slot, VAL, future, "", "")
       ).to.be.revertedWith("RWA: contract hash required");
     });
   });
 
   // ═══════════════════════════════════════
   //  3. ERC-3525 query (overloaded functions)
   // ═══════════════════════════════════════
   describe("3. ERC-3525 queries", function () {
     it("balanceOf(tokenId) returns face value", async function () {
       const bal = await $(coreAsset, "balanceOf(uint256)")(1n);
       expect(bal).to.equal(10_000_000_00n);
     });
     it("ownerOf returns kedali", async function () {
       expect(await coreAsset.ownerOf(1n)).to.equal(kedali.address);
     });
     it("slotOf returns non-zero", async function () {
       expect(await coreAsset.slotOf(1n)).to.not.equal(0n);
     });
     it("balanceOf(address) counts tokens", async function () {
       const bal1 = await $(coreAsset, "balanceOf(address)")(kedali.address);
       expect(bal1).to.equal(1n);
       const bal2 = await $(coreAsset, "balanceOf(address)")(byd.address);
       expect(bal2).to.equal(0n);
     });
   });
 
   // ═══════════════════════════════════════
   //  4. Split / Transfer (overloaded)
   // ═══════════════════════════════════════
   describe("4. Split & Transfer", function () {
     it("kedali splits 3M to juyong", async function () {
       // use qualified approve(uint256,address,uint256)
       await $(coreAsset.connect(kedali), "approve(uint256,address,uint256)")(1n, kedali.address, 3_000_000_00n);
       const tx = await $(coreAsset.connect(kedali), "transferFrom(uint256,address,uint256)")(1n, juyong.address, 3_000_000_00n);
       await expect(tx).to.emit(coreAsset, "AssetSplit").withArgs(1n, 2n, 3_000_000_00n);
 
       expect(await $(coreAsset, "balanceOf(uint256)")(1n)).to.equal(7_000_000_00n);
       expect(await $(coreAsset, "balanceOf(uint256)")(2n)).to.equal(3_000_000_00n);
       expect(await coreAsset.ownerOf(2n)).to.equal(juyong.address);
       expect(await coreAsset.totalSupply()).to.equal(2n);
     });
     it("juyong splits 1M to changyuan", async function () {
       await $(coreAsset.connect(juyong), "approve(uint256,address,uint256)")(2n, juyong.address, 1_000_000_00n);
       const tx = await $(coreAsset.connect(juyong), "transferFrom(uint256,address,uint256)")(2n, changyuan.address, 1_000_000_00n);
       await expect(tx).to.emit(coreAsset, "AssetSplit").withArgs(2n, 3n, 1_000_000_00n);
 
       expect(await $(coreAsset, "balanceOf(uint256)")(2n)).to.equal(2_000_000_00n);
       expect(await $(coreAsset, "balanceOf(uint256)")(3n)).to.equal(1_000_000_00n);
       expect(await coreAsset.ownerOf(3n)).to.equal(changyuan.address);
       expect(await coreAsset.totalSupply()).to.equal(3n);
     });
     it("over-transfer fails", async function () {
       await expect(
         $(coreAsset.connect(changyuan), "transferFrom(uint256,address,uint256)")(3n, byd.address, 9_999_999_99n)
       ).to.be.revertedWith("RWA: insufficient value");
     });
     it("unauthorized transfer fails", async function () {
       await expect(
         $(coreAsset.connect(deployer), "transferFrom(uint256,address,uint256)")(1n, byd.address, 1_000_000n)
       ).to.be.revertedWith("RWA: not authorized");
     });
   });
 
   // ═══════════════════════════════════════
   //  5. Asset status management
   // ═══════════════════════════════════════
   describe("5. Asset status", function () {
     it("issuer can revoke", async function () {
       await coreAsset.connect(byd).revokeAsset(1n, "contract terminated");
       const info = await coreAsset.getAssetInfo(1n);
       expect(info.status).to.equal(2); // Revoked
     });
     it("non-issuer cannot revoke", async function () {
       await expect(
         coreAsset.connect(kedali).revokeAsset(2n, "unauthorized")
       ).to.be.revertedWith("RWA: not the issuer");
     });
     it("revoked asset cannot be transferred", async function () {
       await expect(
         $(coreAsset.connect(byd), "transferFrom(uint256,address,uint256)")(1n, kedali.address, 1_000_000n)
       ).to.be.revertedWith("RWA: token not active");
     });
 
     it("financial institution can freeze active asset", async function () {
       // mint a new token for freeze test; compute new slot
       const future = Math.floor(Date.now() / 1000) + 3600 * 24;
       const slot = await coreAsset.computeSlot(byd.address, future, 2);
       await coreAsset.connect(byd).mintRWAAsset(
         kedali.address, slot, 5_000_000_00n, future,
         ethers.keccak256(ethers.toUtf8Bytes("FREEZE-TEST")), ""
       );
       const tokenId4 = 4n;
       await coreAsset.connect(ccb).setAssetFrozen(tokenId4, true);
       const info = await coreAsset.getAssetInfo(tokenId4);
       expect(info.status).to.equal(1); // Frozen
 
       // also verify cannot transfer
       await expect(
         $(coreAsset.connect(kedali), "transferFrom(uint256,address,uint256)")(tokenId4, juyong.address, 1_000_000n)
       ).to.be.revertedWith("RWA: token not active");
 
       // thaw
       await coreAsset.connect(ccb).setAssetFrozen(tokenId4, false);
       const info2 = await coreAsset.getAssetInfo(tokenId4);
       expect(info2.status).to.equal(0); // Active
     });
     it("non-financial cannot freeze", async function () {
       await expect(
         coreAsset.connect(kedali).setAssetFrozen(2n, true)
       ).to.be.revertedWith("RWA: not financial institution or admin");
     });
   });
 
   // ═══════════════════════════════════════
   //  6. RWA query interfaces
   // ═══════════════════════════════════════
   describe("6. RWA queries", function () {
     it("getAssetInfo returns full info", async function () {
       const info = await coreAsset.getAssetInfo(1n);
       expect(info.issuer).to.equal(byd.address);
       expect(info.faceValue).to.equal(10_000_000_00n);
       expect(info.currentValue).to.equal(7_000_000_00n);
       expect(info.owner).to.equal(kedali.address);
     });
     it("getIssuedAssets works", async function () {
       const assets = await coreAsset.getIssuedAssets(byd.address);
       expect(assets.length).to.be.greaterThanOrEqual(1);
       // Check that tokenId 1 (BigInt) is included
       expect(assets.some(a => a === 1n)).to.be.true;
     });
     it("getHeldAssets works", async function () {
       const held = await coreAsset.getHeldAssets(changyuan.address);
       expect(held.some(a => a === 3n)).to.be.true;
     });
   });
 
   // ═══════════════════════════════════════
   //  7. ERC-721 compatibility
   // ═══════════════════════════════════════
   describe("7. ERC-721 compat", function () {
     it("setApprovalForAll works", async function () {
       await coreAsset.connect(kedali).setApprovalForAll(deployer.address, true);
       expect(await coreAsset.isApprovedForAll(kedali.address, deployer.address)).to.be.true;
     });
     it("supportsInterface ERC-721", async function () {
       expect(await coreAsset.supportsInterface("0x80ac58cd")).to.be.true;
     });
   });
 
   // ═══════════════════════════════════════
   //  8. End-to-end BYD scenario
   // ═══════════════════════════════════════
   describe("8. BYD E2E scenario", function () {
     it("BYD issues 10M -> Kedali splits 3M -> Juyong splits 1M -> Changyuan", async function () {
       const slot = await coreAsset.computeSlot(
         byd.address, Math.floor(Date.now() / 1000) + 180 * 86400, 1
       );
       const H = ethers.keccak256(ethers.toUtf8Bytes("BYD-2026-BATTERY-001"));
       const tx1 = await coreAsset.connect(byd).mintRWAAsset(
         kedali.address, slot, 10_000_000_00n,
         Math.floor(Date.now() / 1000) + 180 * 86400, H,
         "https://rwa.example/assets/byd-001.json"
       );
       await tx1.wait();
       const t5 = 5n;
 
       // split 3M from t5 -> juyong (new token = 6)
       await $(coreAsset.connect(kedali), "approve(uint256,address,uint256)")(t5, kedali.address, 3_000_000_00n);
       await $(coreAsset.connect(kedali), "transferFrom(uint256,address,uint256)")(t5, juyong.address, 3_000_000_00n);
       const t6 = 6n;
 
       // split 1M from t6 -> changyuan (new token = 7)
       await $(coreAsset.connect(juyong), "approve(uint256,address,uint256)")(t6, juyong.address, 1_000_000_00n);
       await $(coreAsset.connect(juyong), "transferFrom(uint256,address,uint256)")(t6, changyuan.address, 1_000_000_00n);
       const t7 = 7n;
 
       // verify
       const info = await coreAsset.getAssetInfo(t7);
       expect(info.issuer).to.equal(byd.address);
       expect(info.currentValue).to.equal(1_000_000_00n);
       expect(info.owner).to.equal(changyuan.address);
 
       const held = await coreAsset.getHeldAssets(changyuan.address);
       expect(held.some(a => a === t7)).to.be.true;
 
       console.log("\n  E2E BYD scenario verified:");
       console.log("     Token #5: BYD issued 10M to Kedali");
       console.log("     Token #6: Split 3M to Juyong");
       console.log("     Token #7: Split 1M to Changyuan (3-tier credit penetration)");
     });
   });
 });
