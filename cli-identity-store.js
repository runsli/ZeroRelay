/**
 * CLI 身份存储：0600 权限 + 可选口令加密（ZERO_RELAY_PASSPHRASE）
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const IDENTITY_PLAIN = 'identity.json';
const IDENTITY_ENC = 'identity.enc.json';
const TLS_PINS_PLAIN = 'tls-pins.json';
const TLS_PINS_ENC = 'tls-pins.enc.json';
const SCRYPT_N = 16384;
const SCRYPT_R = 8;
const SCRYPT_P = 1;

function secureWrite(filePath, data) {
  fs.writeFileSync(filePath, data, { mode: 0o600 });
  try {
    fs.chmodSync(filePath, 0o600);
  } catch {
    /* ignore */
  }
}

function getPassphrase() {
  return process.env.ZERO_RELAY_PASSPHRASE || null;
}

function encryptSealed(value, passphrase) {
  const salt = crypto.randomBytes(16);
  const key = crypto.scryptSync(passphrase, salt, 32, { N: SCRYPT_N, r: SCRYPT_R, p: SCRYPT_P });
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const plain = JSON.stringify(value);
  const enc = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return JSON.stringify({
    v: 1,
    salt: salt.toString('base64'),
    iv: iv.toString('base64'),
    tag: tag.toString('base64'),
    ciphertext: enc.toString('base64'),
  });
}

function decryptSealed(blob, passphrase) {
  const json = JSON.parse(blob);
  const salt = Buffer.from(json.salt, 'base64');
  const iv = Buffer.from(json.iv, 'base64');
  const tag = Buffer.from(json.tag, 'base64');
  const ciphertext = Buffer.from(json.ciphertext, 'base64');
  const key = crypto.scryptSync(passphrase, salt, 32, { N: SCRYPT_N, r: SCRYPT_R, p: SCRYPT_P });
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);
  const plain = Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString('utf8');
  return JSON.parse(plain);
}

function encryptIdentity(identity, passphrase) {
  return encryptSealed(identity, passphrase);
}

function decryptIdentity(blob, passphrase) {
  return decryptSealed(blob, passphrase);
}

function loadIdentity(dataDir, createFn) {
  const encPath = path.join(dataDir, IDENTITY_ENC);
  const plainPath = path.join(dataDir, IDENTITY_PLAIN);
  const passphrase = getPassphrase();

  if (fs.existsSync(encPath)) {
    if (!passphrase) {
      throw new Error('已加密身份文件存在，请设置环境变量 ZERO_RELAY_PASSPHRASE');
    }
    return decryptIdentity(fs.readFileSync(encPath, 'utf8'), passphrase);
  }

  if (fs.existsSync(plainPath)) {
    const identity = JSON.parse(fs.readFileSync(plainPath, 'utf8'));
    if (passphrase) {
      secureWrite(encPath, encryptIdentity(identity, passphrase));
      fs.unlinkSync(plainPath);
    }
    return identity;
  }

  const identity = createFn();
  saveIdentity(dataDir, identity);
  return identity;
}

function saveIdentity(dataDir, identity) {
  const passphrase = getPassphrase();
  if (passphrase) {
    secureWrite(path.join(dataDir, IDENTITY_ENC), encryptIdentity(identity, passphrase));
    const plain = path.join(dataDir, IDENTITY_PLAIN);
    if (fs.existsSync(plain)) fs.unlinkSync(plain);
  } else {
    secureWrite(path.join(dataDir, IDENTITY_PLAIN), JSON.stringify(identity, null, 2));
  }
}

function loadTlsPins(dataDir) {
  const encPath = path.join(dataDir, TLS_PINS_ENC);
  const plainPath = path.join(dataDir, TLS_PINS_PLAIN);
  const passphrase = getPassphrase();

  if (fs.existsSync(encPath)) {
    if (!passphrase) {
      throw new Error('已加密 TLS pin 文件存在，请设置环境变量 ZERO_RELAY_PASSPHRASE');
    }
    try {
      return decryptSealed(fs.readFileSync(encPath, 'utf8'), passphrase);
    } catch {
      return {};
    }
  }

  if (!fs.existsSync(plainPath)) return {};
  try {
    const pins = JSON.parse(fs.readFileSync(plainPath, 'utf8'));
    if (passphrase) {
      secureWrite(encPath, encryptSealed(pins, passphrase));
      fs.unlinkSync(plainPath);
    }
    return pins;
  } catch {
    return {};
  }
}

function saveTlsPins(dataDir, pins) {
  const passphrase = getPassphrase();
  const encPath = path.join(dataDir, TLS_PINS_ENC);
  const plainPath = path.join(dataDir, TLS_PINS_PLAIN);
  if (passphrase) {
    secureWrite(encPath, encryptSealed(pins, passphrase));
    if (fs.existsSync(plainPath)) fs.unlinkSync(plainPath);
  } else {
    secureWrite(plainPath, JSON.stringify(pins, null, 2));
    if (fs.existsSync(encPath)) fs.unlinkSync(encPath);
  }
}

function rememberTlsPin(dataDir, host, pin) {
  const all = loadTlsPins(dataDir);
  const set = new Set(all[host] || []);
  set.add(pin);
  all[host] = [...set];
  saveTlsPins(dataDir, all);
}

function getTlsPinsForHost(dataDir, host) {
  return loadTlsPins(dataDir)[host] || [];
}

function trustTlsPin(dataDir, host, pin) {
  const all = loadTlsPins(dataDir);
  all[host] = [pin];
  saveTlsPins(dataDir, all);
}

module.exports = {
  loadIdentity,
  saveIdentity,
  loadTlsPins,
  rememberTlsPin,
  getTlsPinsForHost,
  trustTlsPin,
};
