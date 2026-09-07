const { expect } = require("chai");
const { generateKey, encryptSensitive, decryptSensitive, maskSensitive } = require("../lib/privacy");

describe("AES-256-GCM privacy utilities", function () {
  it("encrypts and decrypts sensitive business data with authenticated AAD", function () {
    const key = generateKey();
    const payload = encryptSensitive("采购单价: 128.50", key, "asset-1:purchase-price:v1");
    expect(payload.algorithm).to.equal("AES-256-GCM");
    expect(payload.ciphertext).to.not.contain("128.50");
    expect(decryptSensitive(payload, key)).to.equal("采购单价: 128.50");
  });

  it("rejects tampered ciphertext and provides deterministic masking modes", function () {
    const key = generateKey();
    const payload = encryptSensitive("contract-details", key);
    const ciphertext = Buffer.from(payload.ciphertext, "base64url");
    ciphertext[0] ^= 1;
    payload.ciphertext = ciphertext.toString("base64url");
    expect(() => decryptSensitive(payload, key)).to.throw();
    expect(maskSensitive("123456", "masked")).to.equal("1****6");
    expect(maskSensitive("123456", "range")).to.equal("[REDACTED_RANGE]");
    expect(maskSensitive("123456", "hash")).to.have.length(64);
  });
});
