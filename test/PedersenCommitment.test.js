const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("PedersenCommitment", function () {
  it("opens a commitment with the original value and blinding factor", async function () {
    const Factory = await ethers.getContractFactory("PedersenCommitment");
    const pedersen = await Factory.deploy();
    const commitment = await pedersen.commit(12345n, 67890n);
    expect(await pedersen.verifyOpening(commitment, 12345n, 67890n)).to.equal(true);
    expect(await pedersen.verifyOpening(commitment, 12346n, 67890n)).to.equal(false);
  });

  it("verifies multiplicative parent/child commitment conservation", async function () {
    const Factory = await ethers.getContractFactory("PedersenCommitment");
    const pedersen = await Factory.deploy();
    const c1 = await pedersen.commit(300n, 111n);
    const c2 = await pedersen.commit(700n, 222n);
    const parent = await pedersen.commit(1000n, 333n);
    expect(await pedersen.verifySplit(parent, c1, c2)).to.equal(true);
    expect(await pedersen.verifySplit(parent, c1, await pedersen.commit(701n, 222n))).to.equal(false);
  });

  it("stores only the commitment value for an asset and verifies stored children", async function () {
    const Factory = await ethers.getContractFactory("PedersenCommitment");
    const pedersen = await Factory.deploy();
    const parent = ethers.id("parent");
    const child1 = ethers.id("child-1");
    const child2 = ethers.id("child-2");
    await pedersen.storeCommitment(parent, 1000n, 333n);
    await pedersen.storeCommitment(child1, 300n, 111n);
    await pedersen.storeCommitment(child2, 700n, 222n);
    expect(await pedersen.commitments(parent)).to.not.equal(0n);
    await expect(pedersen.verifyStoredSplit(parent, child1, child2)).to.emit(pedersen, "CommitmentVerified");
  });
});
