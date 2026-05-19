/**
 * 中继层安全（与 server/src/relay-security.ts、Android RelayCrypto 对齐）
 */
const crypto = require('crypto');
const { ed25519 } = require('@noble/curves/ed25519');

const ROOM_ACCESS_INFO = 'zero-relay-room-access-v1';
const SIGN_HKDF_SALT = 'zero-relay-sign-v1';
const SIGN_SEED_INFO = 'ed25519-seed';

function hkdfExpand(ikm, salt, info, len = 32) {
  return crypto.hkdfSync('sha256', ikm, salt, Buffer.from(info, 'utf8'), len);
}

function deriveRoomAccessToken(roomSecret, roomId) {
  return crypto
    .createHmac('sha256', roomSecret)
    .update(ROOM_ACCESS_INFO)
    .update(roomId, 'utf8')
    .digest('base64');
}

function routeHashFromToken(tokenB64) {
  try {
    const raw = Buffer.from(tokenB64, 'base64');
    if (raw.length !== 32) return null;
    return crypto.createHash('sha256').update(raw).digest();
  } catch {
    return null;
  }
}

function routeHashB64FromToken(tokenB64) {
  const hash = routeHashFromToken(tokenB64);
  return hash ? hash.toString('base64') : null;
}

function routeIdFromToken(tokenB64) {
  const hash = routeHashFromToken(tokenB64);
  return hash ? hash.toString('base64url') : null;
}

function deriveSigningKeys(identityPrivateKeyB64) {
  const seed = new Uint8Array(
    hkdfExpand(
      Buffer.from(identityPrivateKeyB64, 'base64'),
      Buffer.from(SIGN_HKDF_SALT, 'utf8'),
      SIGN_SEED_INFO,
    ),
  );
  const signPublic = ed25519.getPublicKey(seed);
  return {
    signPrivate: seed,
    signPublic: Buffer.from(signPublic).toString('base64'),
  };
}

function buildSignPayload(routeId, senderId, timestamp, ciphertext, iv, tag) {
  return [routeId, senderId, String(timestamp), ciphertext, iv, tag].join('\n');
}

function signMessage(keys, routeId, senderId, timestamp, ciphertext, iv, tag) {
  const payload = buildSignPayload(routeId, senderId, timestamp, ciphertext, iv, tag);
  const sig = ed25519.sign(Buffer.from(payload, 'utf8'), keys.signPrivate);
  return Buffer.from(sig).toString('base64');
}

function verifyMessage(msg) {
  const { senderPk, signPk, sig, senderId, routeId, ciphertext, iv, tag, timestamp } = msg;
  if (!senderPk || !signPk || !sig) return false;
  if (senderIdFromPublicKey(senderPk) !== senderId) return false;
  const payload = buildSignPayload(routeId, senderId, timestamp, ciphertext, iv, tag);
  try {
    return ed25519.verify(
      Buffer.from(sig, 'base64'),
      Buffer.from(payload, 'utf8'),
      Buffer.from(signPk, 'base64'),
    );
  } catch {
    return false;
  }
}

function senderIdFromPublicKey(pubB64) {
  const hash = crypto.createHash('sha256').update(Buffer.from(pubB64, 'base64')).digest();
  return (
    'id_' +
    hash
      .subarray(0, 6)
      .toString('base64url')
      .replace(/-/g, 'x')
      .replace(/_/g, 'y')
  );
}

function certPinFromPeerCert(cert) {
  const spki = cert.pubkey || cert.publicKey;
  if (!spki) return null;
  const hash = crypto.createHash('sha256').update(spki).digest('base64');
  return `sha256/${hash}`;
}

module.exports = {
  deriveRoomAccessToken,
  routeHashB64FromToken,
  routeIdFromToken,
  deriveSigningKeys,
  signMessage,
  verifyMessage,
  senderIdFromPublicKey,
  certPinFromPeerCert,
};
