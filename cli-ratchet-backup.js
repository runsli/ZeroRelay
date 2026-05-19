/**
 * 棘轮备份（与 Android RatchetBackup 对齐：PBKDF2 120000 + data 字段含密文+tag）
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const BACKUP_VERSION = 1;
const PBKDF2_ITERATIONS = 120_000;

/** @deprecated 旧版 scrypt 备份 */
const LEGACY_SCRYPT = { N: 16384, r: 8, p: 1 };

function deriveKey(passphrase, salt) {
  return crypto.pbkdf2Sync(passphrase, salt, PBKDF2_ITERATIONS, 32, 'sha256');
}

function encryptRatchetBackup(passphrase, ratchetsObject) {
  const salt = crypto.randomBytes(16);
  const key = deriveKey(passphrase, salt);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(ratchetsObject), 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  const data = Buffer.concat([ciphertext, tag]);
  return JSON.stringify({
    v: BACKUP_VERSION,
    salt: salt.toString('base64'),
    iv: iv.toString('base64'),
    data: data.toString('base64'),
  });
}

function decryptRatchetBackup(passphrase, blob) {
  const json = JSON.parse(blob);
  if (json.v !== BACKUP_VERSION) throw new Error('备份版本不支持');

  if (json.data && !json.ciphertext) {
    const salt = Buffer.from(json.salt, 'base64');
    const iv = Buffer.from(json.iv, 'base64');
    const packed = Buffer.from(json.data, 'base64');
    if (packed.length < 17) throw new Error('备份数据损坏');
    const tag = packed.subarray(packed.length - 16);
    const ciphertext = packed.subarray(0, packed.length - 16);
    const key = deriveKey(passphrase, salt);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(tag);
    const plain = Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString('utf8');
    return JSON.parse(plain);
  }

  if (json.ciphertext && json.tag) {
    const salt = Buffer.from(json.salt, 'base64');
    const iv = Buffer.from(json.iv, 'base64');
    const tag = Buffer.from(json.tag, 'base64');
    const ciphertext = Buffer.from(json.ciphertext, 'base64');
    const key = crypto.scryptSync(passphrase, salt, 32, LEGACY_SCRYPT);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(tag);
    const plain = Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString('utf8');
    return JSON.parse(plain);
  }

  throw new Error('无法识别的备份格式');
}

function exportToFile(dataDir, passphrase, ratchets) {
  const blob = encryptRatchetBackup(passphrase, ratchets);
  const out = path.join(dataDir, 'ratchet-backup.enc.json');
  fs.writeFileSync(out, blob, { mode: 0o600 });
  return out;
}

function importFromFile(dataDir, passphrase) {
  const file = path.join(dataDir, 'ratchet-backup.enc.json');
  if (!fs.existsSync(file)) throw new Error(`未找到 ${file}`);
  return decryptRatchetBackup(passphrase, fs.readFileSync(file, 'utf8'));
}

function exportToBlob(passphrase, ratchets) {
  return encryptRatchetBackup(passphrase, ratchets);
}

module.exports = {
  encryptRatchetBackup,
  decryptRatchetBackup,
  exportToFile,
  importFromFile,
  exportToBlob,
  PBKDF2_ITERATIONS,
};
