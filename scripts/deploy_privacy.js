const hre = require("hardhat");

async function main() {
  const [admin, enterprise, supplier, bank] = await hre.ethers.getSigners();
  const AccessControlFactory = await hre.ethers.getContractFactory("contracts/AccessControl.sol:AccessControl");
  const acl = await AccessControlFactory.deploy();
  await acl.waitForDeployment();

  const PedersenFactory = await hre.ethers.getContractFactory("PedersenCommitment");
  const pedersen = await PedersenFactory.deploy();
  await pedersen.waitForDeployment();

  await (await acl.grantRole(await acl.CORE_ENTERPRISE_ROLE(), enterprise.address)).wait();
  await (await acl.grantRole(await acl.SUPPLIER_ROLE(), supplier.address)).wait();
  await (await acl.grantRole(await acl.BANK_ROLE(), bank.address)).wait();

  console.log(JSON.stringify({
    network: hre.network.name,
    admin: admin.address,
    accessControl: await acl.getAddress(),
    pedersenCommitment: await pedersen.getAddress(),
    roles: {
      coreEnterprise: enterprise.address,
      supplier: supplier.address,
      bank: bank.address,
    },
  }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
