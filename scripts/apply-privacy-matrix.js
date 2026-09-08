const fs = require("node:fs");
const path = require("node:path");
const hre = require("hardhat");

const ROLE_NAMES = [
  "ADMIN_ROLE",
  "CORE_ENTERPRISE_ROLE",
  "SUPPLIER_ROLE",
  "FINANCIAL_INSTITUTION_ROLE",
];

async function main() {
  const address = process.env.ACCESS_CONTROL_ADDRESS;
  if (!address) {
    throw new Error("ACCESS_CONTROL_ADDRESS is required");
  }

  const matrixPath = process.env.PRIVACY_MATRIX_PATH
    ? path.resolve(process.env.PRIVACY_MATRIX_PATH)
    : path.resolve(__dirname, "../config/privacy-key-matrix.json");
  const matrix = JSON.parse(fs.readFileSync(matrixPath, "utf8"));
  const fields = matrix.fields || [];
  const [admin] = await hre.ethers.getSigners();
  const acl = await hre.ethers.getContractAt("AccessControl", address, admin);
  const roleIds = {};
  for (const roleName of ROLE_NAMES) {
    roleIds[roleName] = await acl[roleName]();
  }

  const resourceId = hre.ethers.id(matrix.resourceId || "asset");
  for (const entry of fields) {
    if (!entry.field || !entry.ownerRole || !ROLE_NAMES.includes(entry.ownerRole)) {
      throw new Error(`Invalid privacy matrix entry: ${JSON.stringify(entry)}`);
    }
    for (const roleName of entry.readRoles || []) {
      if (!ROLE_NAMES.includes(roleName)) throw new Error(`Unsupported role in privacy matrix: ${roleName}`);
    }
    const fieldId = hre.ethers.id(entry.field);
    const allowedRoles = new Set(entry.readRoles || []);
    for (const roleName of ROLE_NAMES) {
      if (!(roleName in roleIds)) continue;
      const allowed = allowedRoles.has(roleName);
      const tx = await acl.setFieldRolePermission(resourceId, fieldId, roleIds[roleName], allowed);
      await tx.wait();
      console.log(`${entry.field}: ${roleName}=${allowed}`);
    }
  }

  console.log(`Applied ${fields.length} field policies to ${address}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
