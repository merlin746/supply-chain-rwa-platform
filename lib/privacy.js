const crypto = require("node:crypto");

const KEY_BYTES = 32;
const IV_BYTES = 12;
const TAG_BYTES = 16;

function assertKey(key) {
  if (!Buffer.isBuffer(key) || key.length !== KEY_BYTES) {
    throw new TypeError("AES-256 key must be a 32-byte Buffer");
  }
}

function generateKey() {
  return crypto.randomBytes(KEY_BYTES);
}

function encryptSensitive(plaintext, key, associatedData = "") {
  assertKey(key);
  const iv = crypto.randomBytes(IV_BYTES);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  if (associatedData) cipher.setAAD(Buffer.from(associatedData));
  const ciphertext = Buffer.concat([cipher.update(String(plaintext), "utf8"), cipher.final()]);
  return {
    algorithm: "AES-256-GCM",
    iv: iv.toString("base64url"),
    ciphertext: ciphertext.toString("base64url"),
    authTag: cipher.getAuthTag().toString("base64url"),
    aad: Buffer.from(associatedData).toString("base64url"),
  };
}

function decryptSensitive(payload, key) {
  assertKey(key);
  if (!payload || payload.algorithm !== "AES-256-GCM") throw new TypeError("Unsupported encryption payload");
  const iv = Buffer.from(payload.iv, "base64url");
  const ciphertext = Buffer.from(payload.ciphertext, "base64url");
  const authTag = Buffer.from(payload.authTag, "base64url");
  if (iv.length !== IV_BYTES || authTag.length !== TAG_BYTES) throw new TypeError("Invalid AES-GCM payload");
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  const aad = Buffer.from(payload.aad || "", "base64url");
  if (aad.length) decipher.setAAD(aad);
  decipher.setAuthTag(authTag);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8");
}

function maskSensitive(value, mode = "masked") {
  const text = String(value);
  if (mode === "hash") return crypto.createHash("sha256").update(text).digest("hex");
  if (mode === "range") return "[REDACTED_RANGE]";
  if (text.length <= 2) return "*".repeat(text.length);
  return `${text[0]}${"*".repeat(text.length - 2)}${text[text.length - 1]}`;
}

module.exports = { generateKey, encryptSensitive, decryptSensitive, maskSensitive };
