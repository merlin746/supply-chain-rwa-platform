const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("AccessControl RBAC", function () {
  it("grants and revokes field-level permissions for the four roles", async function () {
    const [admin, enterprise, supplier, bank] = await ethers.getSigners();
    const Factory = await ethers.getContractFactory("contracts/AccessControl.sol:AccessControl");
    const acl = await Factory.deploy();
    const resource = ethers.id("asset-1");
    const field = ethers.id("purchase-price");
    const core = await acl.CORE_ENTERPRISE_ROLE();
    const sup = await acl.SUPPLIER_ROLE();
    const bankRole = await acl.FINANCIAL_INSTITUTION_ROLE();

    await acl.grantRole(core, enterprise.address);
    await acl.grantRole(sup, supplier.address);
    await acl.grantRole(bankRole, bank.address);
    await acl.setFieldRolePermission(resource, field, core, true);
    await acl.setFieldRolePermission(resource, field, bankRole, true);

    expect(await acl.canReadField(resource, field, enterprise.address)).to.equal(true);
    expect(await acl.canReadField(resource, field, bank.address)).to.equal(true);
    expect(await acl.canReadField(resource, field, supplier.address)).to.equal(false);

    await acl.revokeRole(bankRole, bank.address);
    expect(await acl.canReadField(resource, field, bank.address)).to.equal(false);
    await expect(acl.connect(supplier).requireCanReadField(resource, field))
      .to.be.revertedWith("AccessControl: field read denied");
    expect(await acl.hasRole(await acl.ADMIN_ROLE(), admin.address)).to.equal(true);
    expect(await acl.BANK_ROLE()).to.equal(bankRole);
  });
});
