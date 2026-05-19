/**
 * 备份口令策略（与 Android HomeViewModel.validateRatchetPassphrase 一致）
 */
const ERR = require('./cli-errors');

const MIN_LENGTH = 8;

function validatePassphrase(pass) {
  const p = String(pass || '');
  if (p.length < MIN_LENGTH) {
    return { ok: false, error: ERR.RATCHET_PASSPHRASE };
  }
  return { ok: true, passphrase: p };
}

function requirePassphrase(pass) {
  const result = validatePassphrase(pass);
  if (!result.ok) throw new Error(result.error);
  return result.passphrase;
}

module.exports = { validatePassphrase, requirePassphrase, MIN_LENGTH };
