/**
 * Node/CJS 版中继安全（与 src/relay-security.ts 行为一致）
 */
const crypto = require('crypto');
const { ed25519 } = require('@noble/curves/ed25519');

const LIMITS = {
  maxJsonBytes: 64 * 1024,
  maxFieldChars: 48 * 1024,
  maxRoomIdLen: 256,
  maxSenderIdLen: 64,
  roomTokenBytes: 32,
  sendPerIpPerMin: 30,
  sendPerRoomPerMin: 120,
  pollPerIpPerMin: 120,
  wsPerIpPerMin: 60,
};

const MAX_TIMESTAMP_SKEW_MS = 10 * 60 * 1000;
const REPLAY_DEDUP_TTL_SEC = 20 * 60;
const ROOM_GATE_REBIND_WINDOW_MS = 0;
const WS_AUTH_TIMEOUT_MS = 10_000;

const ipBuckets = new Map();
const roomBuckets = new Map();
const wsIpBuckets = new Map();
const memoryGates = new Map();
const memoryGateAt = new Map();
const memoryGateRebind = new Set();
const memorySeen = new Map();

function checkBucket(map, key, limit) {
  const now = Date.now();
  const bucket = map.get(key);
  if (!bucket || now >= bucket.resetAt) {
    map.set(key, { count: 1, resetAt: now + 60_000 });
    return true;
  }
  if (bucket.count >= limit) return false;
  bucket.count += 1;
  return true;
}

function checkRateLimit(_kv, ip, roomId, endpoint) {
  const clientIp = ip || 'unknown';
  if (endpoint === 'send') {
    if (!checkBucket(ipBuckets, `send-ip:${clientIp}`, LIMITS.sendPerIpPerMin)) {
      return 'rate limit: too many send requests from this IP';
    }
    if (!checkBucket(roomBuckets, `send-room:${roomId}`, LIMITS.sendPerRoomPerMin)) {
      return 'rate limit: too many send requests for this room';
    }
  } else if (endpoint === 'messages') {
    if (!checkBucket(ipBuckets, `poll-ip:${clientIp}`, LIMITS.pollPerIpPerMin)) {
      return 'rate limit: too many poll requests from this IP';
    }
  } else if (!checkBucket(wsIpBuckets, `ws-ip:${clientIp}`, LIMITS.wsPerIpPerMin)) {
    return 'rate limit: too many WebSocket connections from this IP';
  }
  return null;
}

function validateMessageTimestamp(timestamp) {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return 'invalid timestamp';
  if (Math.abs(Date.now() - timestamp) > MAX_TIMESTAMP_SKEW_MS) {
    return 'timestamp outside allowed window';
  }
  return null;
}

function validateFieldSizes(body) {
  for (const key of ['roomId', 'ciphertext', 'iv', 'tag', 'senderId', 'senderPk', 'signPk', 'sig']) {
    const val = body[key];
    if (val == null) continue;
    if (typeof val !== 'string') return `invalid field type: ${key}`;
    if (val.length > LIMITS.maxFieldChars) return `field too large: ${key}`;
  }
  return null;
}

function hashRoomToken(tokenB64) {
  try {
    const raw = Buffer.from(tokenB64, 'base64');
    if (raw.length !== LIMITS.roomTokenBytes) return null;
    return crypto.createHash('sha256').update(raw).digest();
  } catch {
    return null;
  }
}

function parseRouteHashB64(b64) {
  try {
    const normalized = String(b64).replace(/-/g, '+').replace(/_/g, '/');
    const pad = normalized.length % 4 === 0 ? '' : '='.repeat(4 - (normalized.length % 4));
    const raw = Buffer.from(normalized + pad, 'base64');
    if (raw.length !== LIMITS.roomTokenBytes) return null;
    return raw;
  } catch {
    return null;
  }
}

function tryRebindRoomGate() {
  return false;
}

function routeIdFromTokenHash(tokenHash) {
  return Buffer.from(tokenHash).toString('base64url');
}

function verifyRouteHashMemory(routeHashBuf, options = {}) {
  if (!routeHashBuf || routeHashBuf.length !== LIMITS.roomTokenBytes) {
    return { error: 'invalid route hash' };
  }
  const routeId = routeIdFromTokenHash(routeHashBuf);
  const messageCount = options.messageCount ?? 0;
  const stored = memoryGates.get(routeId);
  if (stored) {
    if (!crypto.timingSafeEqual(routeHashBuf, stored)) {
      if (tryRebindRoomGate(routeId, routeHashBuf, messageCount)) return { routeId };
      return { error: 'room token mismatch' };
    }
    return { routeId };
  }
  memoryGates.set(routeId, routeHashBuf);
  memoryGateAt.set(routeId, Date.now());
  return { routeId };
}

function verifyRoomTokenMemory(tokenB64, options = {}) {
  if (!tokenB64) return { error: 'missing room token' };
  const tokenHash = hashRoomToken(tokenB64);
  if (!tokenHash) return { error: 'invalid room token format' };
  return verifyRouteHashMemory(tokenHash, options);
}

function buildSignPayload(fields) {
  return [
    fields.routeId,
    fields.senderId,
    String(fields.timestamp),
    fields.ciphertext,
    fields.iv,
    fields.tag,
  ].join('\n');
}

function buildReplayDedupKey(fields) {
  const ts = fields.timestamp ?? Date.now();
  return crypto
    .createHash('sha256')
    .update(`${fields.routeId}\n${fields.senderId}\n${ts}\n${fields.ciphertext}\n${fields.iv}\n${fields.tag}`)
    .digest('hex');
}

function checkReplayDedup(roomId, dedupKey) {
  const key = `seen:${roomId}:${dedupKey}`;
  const now = Date.now();
  for (const [k, expiry] of memorySeen.entries()) {
    if (expiry <= now) memorySeen.delete(k);
  }
  if (memorySeen.has(key)) return 'duplicate message (replay detected)';
  memorySeen.set(key, now + REPLAY_DEDUP_TTL_SEC * 1000);
  return null;
}

function senderIdFromPublicKey(publicKey) {
  const hash = crypto.createHash('sha256').update(publicKey).digest();
  const suffix = hash.subarray(0, 6).toString('base64url').replace(/-/g, 'x').replace(/_/g, 'y');
  return `id_${suffix}`;
}

function verifyMessageSignature(fields) {
  const { senderPk, signPk, sig, senderId, routeId, ciphertext, iv, tag } = fields;
  if (!senderPk || !signPk || !sig) return 'missing senderPk, signPk, or sig';
  const senderPkBytes = Buffer.from(senderPk, 'base64');
  const signPkBytes = Buffer.from(signPk, 'base64');
  const sigBytes = Buffer.from(sig, 'base64');
  if (senderPkBytes.length !== 32) return 'invalid senderPk';
  if (signPkBytes.length !== 32) return 'invalid signPk';
  if (sigBytes.length !== 64) return 'invalid sig';
  if (senderIdFromPublicKey(senderPkBytes) !== senderId) return 'senderId does not match senderPk';
  const ts = fields.timestamp ?? Date.now();
  const payload = buildSignPayload({ routeId, senderId, ciphertext, iv, tag, timestamp: ts });
  try {
    const valid = ed25519.verify(sigBytes, Buffer.from(payload), signPkBytes);
    if (!valid) return 'invalid message signature';
    return null;
  } catch {
    return 'signature verification failed';
  }
}

function assertMessageAcceptable(fields) {
  const ts = fields.timestamp ?? Date.now();
  const tsErr = validateMessageTimestamp(ts);
  if (tsErr) return tsErr;
  const sigErr = verifyMessageSignature({ ...fields, timestamp: ts });
  if (sigErr) return sigErr;
  const dedupKey = buildReplayDedupKey({ ...fields, timestamp: ts });
  return checkReplayDedup(fields.routeId, dedupKey);
}

module.exports = {
  LIMITS,
  MAX_TIMESTAMP_SKEW_MS,
  REPLAY_DEDUP_TTL_SEC,
  ROOM_GATE_REBIND_WINDOW_MS,
  WS_AUTH_TIMEOUT_MS,
  checkRateLimit,
  validateFieldSizes,
  validateMessageTimestamp,
  verifyRoomTokenMemory,
  verifyRouteHashMemory,
  parseRouteHashB64,
  verifyMessageSignature,
  assertMessageAcceptable,
  senderIdFromPublicKey,
};
