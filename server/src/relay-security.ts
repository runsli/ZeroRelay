/**
 * 中继层安全：房间令牌、Ed25519 签名验证、速率限制、防重放、体积上限
 */

export const LIMITS = {
  maxJsonBytes: 64 * 1024,
  maxFieldChars: 48 * 1024,
  maxRoomIdLen: 256,
  maxSenderIdLen: 64,
  roomTokenBytes: 32,
  sendPerIpPerMin: 30,
  sendPerRoomPerMin: 120,
  pollPerIpPerMin: 120,
  wsPerIpPerMin: 60,
} as const;

/** 签名时间戳允许偏差（毫秒） */
export const MAX_TIMESTAMP_SKEW_MS = 10 * 60 * 1000;

/** 重放去重 KV TTL（秒） */
export const REPLAY_DEDUP_TTL_SEC = 20 * 60;

/** gate 重绑已禁用（防止空房间抢注 routeHash） */
export const ROOM_GATE_REBIND_WINDOW_MS = 0;

/** WebSocket 首帧鉴权超时（毫秒） */
export const WS_AUTH_TIMEOUT_MS = 10_000;

/** 混淆路由桶 ID（base64url SHA-256(roomToken)），非 E2EE roomId */
export const TUNNEL_ROUTE_INFO = 'zero-relay-route-v1';

export interface SignedMessageFields {
  routeId: string;
  senderId: string;
  senderPk: string;
  signPk: string;
  sig: string;
  ciphertext: string;
  iv: string;
  tag: string;
  timestamp?: number;
}

export interface RoomTokenVerifyOptions {
  messageCount?: number;
}

type RateBucket = { count: number; resetAt: number };

const ipBuckets = new Map<string, RateBucket>();
const roomBuckets = new Map<string, RateBucket>();
const wsIpBuckets = new Map<string, RateBucket>();
const memoryGates = new Map<string, Uint8Array>();
const memoryGateAt = new Map<string, number>();
const memoryGateRebind = new Set<string>();
const memorySeen = new Map<string, number>();

function rateKey(kind: string, id: string): string {
  return `${kind}:${id}`;
}

function checkBucket(map: Map<string, RateBucket>, key: string, limit: number): boolean {
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

function currentRateWindow(): number {
  return Math.floor(Date.now() / 60_000);
}

async function checkRateLimitKv(
  kv: KVNamespace,
  bucketKey: string,
  limit: number,
): Promise<boolean> {
  const window = currentRateWindow();
  const kvKey = `rl:${bucketKey}:${window}`;
  const raw = await kv.get(kvKey);
  const count = raw ? parseInt(raw, 10) : 0;
  if (!Number.isFinite(count) || count >= limit) return false;
  await kv.put(kvKey, String(count + 1), { expirationTtl: 120 });
  return true;
}

export async function checkRateLimit(
  kv: KVNamespace | undefined,
  ip: string,
  roomId: string,
  endpoint: 'send' | 'messages' | 'ws',
): Promise<string | null> {
  const clientIp = ip || 'unknown';

  if (kv) {
    if (endpoint === 'send') {
      if (!(await checkRateLimitKv(kv, `send-ip:${clientIp}`, LIMITS.sendPerIpPerMin))) {
        return 'rate limit: too many send requests from this IP';
      }
      if (!(await checkRateLimitKv(kv, `send-room:${roomId}`, LIMITS.sendPerRoomPerMin))) {
        return 'rate limit: too many send requests for this room';
      }
    } else if (endpoint === 'messages') {
      if (!(await checkRateLimitKv(kv, `poll-ip:${clientIp}`, LIMITS.pollPerIpPerMin))) {
        return 'rate limit: too many poll requests from this IP';
      }
    } else if (!(await checkRateLimitKv(kv, `ws-ip:${clientIp}`, LIMITS.wsPerIpPerMin))) {
      return 'rate limit: too many WebSocket connections from this IP';
    }
    return null;
  }

  if (endpoint === 'send') {
    if (!checkBucket(ipBuckets, rateKey('send-ip', clientIp), LIMITS.sendPerIpPerMin)) {
      return 'rate limit: too many send requests from this IP';
    }
    if (!checkBucket(roomBuckets, rateKey('send-room', roomId), LIMITS.sendPerRoomPerMin)) {
      return 'rate limit: too many send requests for this room';
    }
  } else if (endpoint === 'messages') {
    if (!checkBucket(ipBuckets, rateKey('poll-ip', clientIp), LIMITS.pollPerIpPerMin)) {
      return 'rate limit: too many poll requests from this IP';
    }
  } else if (!checkBucket(wsIpBuckets, rateKey('ws-ip', clientIp), LIMITS.wsPerIpPerMin)) {
    return 'rate limit: too many WebSocket connections from this IP';
  }
  return null;
}

export function validateMessageTimestamp(timestamp: number): string | null {
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return 'invalid timestamp';
  }
  const skew = Math.abs(Date.now() - timestamp);
  if (skew > MAX_TIMESTAMP_SKEW_MS) {
    return 'timestamp outside allowed window';
  }
  return null;
}

export function validateFieldSizes(body: Record<string, unknown>): string | null {
  for (const key of ['routeId', 'roomId', 'ciphertext', 'iv', 'tag', 'senderId', 'senderPk', 'signPk', 'sig']) {
    const val = body[key];
    if (val == null) continue;
    if (typeof val !== 'string') return `invalid field type: ${key}`;
    if (val.length > LIMITS.maxFieldChars) return `field too large: ${key}`;
  }
  if (typeof body.roomId === 'string' && body.roomId.length > LIMITS.maxRoomIdLen) {
    return 'roomId too long';
  }
  if (typeof body.senderId === 'string' && body.senderId.length > LIMITS.maxSenderIdLen) {
    return 'senderId too long';
  }
  return null;
}

export function base64ToBytes(b64: string): Uint8Array | null {
  try {
    const normalized = b64.replace(/-/g, '+').replace(/_/g, '/');
    const pad = normalized.length % 4 === 0 ? '' : '='.repeat(4 - (normalized.length % 4));
    const bin = atob(normalized + pad);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

export function bytesToBase64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

export function bytesToBase64Url(bytes: Uint8Array): string {
  return bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function routeIdFromTokenHash(tokenHash: Uint8Array): string {
  return bytesToBase64Url(tokenHash);
}

export async function routeIdFromTokenB64(tokenB64: string): Promise<string | null> {
  const hash = await hashRoomToken(tokenB64);
  if (!hash) return null;
  return routeIdFromTokenHash(hash);
}

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data));
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  const hash = await sha256(data);
  return Array.from(hash)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function concatUtf8(...parts: string[]): Uint8Array {
  const enc = new TextEncoder();
  const chunks = parts.map((p) => enc.encode(p));
  const len = chunks.reduce((n, c) => n + c.length, 0);
  const out = new Uint8Array(len);
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.length;
  }
  return out;
}

function pruneMemorySeen(now: number): void {
  for (const [key, expiry] of memorySeen.entries()) {
    if (expiry <= now) memorySeen.delete(key);
  }
}

export async function buildReplayDedupKey(fields: SignedMessageFields): Promise<string> {
  const ts = fields.timestamp ?? Date.now();
  const material = concatUtf8(
    fields.routeId,
    fields.senderId,
    String(ts),
    fields.ciphertext,
    fields.iv,
    fields.tag,
  );
  return sha256Hex(material);
}

export async function checkReplayDedup(
  kv: KVNamespace | undefined,
  roomId: string,
  dedupKey: string,
): Promise<string | null> {
  const key = `seen:${roomId}:${dedupKey}`;
  if (kv) {
    const exists = await kv.get(key);
    if (exists) return 'duplicate message (replay detected)';
    await kv.put(key, '1', { expirationTtl: REPLAY_DEDUP_TTL_SEC });
    return null;
  }
  const now = Date.now();
  pruneMemorySeen(now);
  if (memorySeen.has(key)) return 'duplicate message (replay detected)';
  memorySeen.set(key, now + REPLAY_DEDUP_TTL_SEC * 1000);
  return null;
}

export async function assertMessageAcceptable(
  kv: KVNamespace | undefined,
  fields: SignedMessageFields,
): Promise<string | null> {
  const ts = fields.timestamp ?? Date.now();
  const tsErr = validateMessageTimestamp(ts);
  if (tsErr) return tsErr;
  const sigErr = await verifyMessageSignature({ ...fields, timestamp: ts });
  if (sigErr) return sigErr;
  const dedupKey = await buildReplayDedupKey({ ...fields, timestamp: ts });
  return checkReplayDedup(kv, fields.routeId, dedupKey);
}

export async function hmacSha256(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  const cryptoKey = await crypto.subtle.importKey(
    'raw',
    key,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  return new Uint8Array(await crypto.subtle.sign('HMAC', cryptoKey, data));
}

export async function hashRoomToken(tokenB64: string): Promise<Uint8Array | null> {
  const raw = base64ToBytes(tokenB64);
  if (!raw || raw.length !== LIMITS.roomTokenBytes) return null;
  return sha256(raw);
}

/** 解析 WS auth 帧中的 routeHash（标准 base64 或 base64url，32 字节） */
export function parseRouteHashB64(b64: string): Uint8Array | null {
  const raw = base64ToBytes(b64);
  if (!raw || raw.length !== LIMITS.roomTokenBytes) return null;
  return raw;
}

async function tryRebindRoomGate(
  _routeId: string,
  _tokenHash: Uint8Array,
  _kv: KVNamespace | undefined,
  _messageCount: number,
): Promise<boolean> {
  return false;
}

async function verifyTokenHash(
  routeId: string,
  tokenHash: Uint8Array,
  kv: KVNamespace | undefined,
  options?: RoomTokenVerifyOptions,
): Promise<string | null> {
  const messageCount = options?.messageCount ?? 0;

  if (kv) {
    const gateKey = `room-gate:${routeId}`;
    const stored = await kv.get(gateKey);
    if (stored) {
      const expected = base64ToBytes(stored);
      if (!expected || !timingSafeEqual(tokenHash, expected)) {
        if (await tryRebindRoomGate(routeId, tokenHash, kv, messageCount)) {
          return null;
        }
        return 'room token mismatch';
      }
      return null;
    }
    const now = Date.now();
    await kv.put(gateKey, bytesToBase64(tokenHash), { expirationTtl: 86400 * 30 });
    await kv.put(`room-gate-at:${routeId}`, String(now), { expirationTtl: 86400 * 30 });
    return null;
  }

  const stored = memoryGates.get(routeId);
  if (stored) {
    if (!timingSafeEqual(tokenHash, stored)) {
      if (await tryRebindRoomGate(routeId, tokenHash, undefined, messageCount)) {
        return null;
      }
      return 'room token mismatch';
    }
    return null;
  }
  const now = Date.now();
  memoryGates.set(routeId, tokenHash);
  memoryGateAt.set(routeId, now);
  return null;
}

/** 校验 roomToken 并返回混淆 routeId（存储桶键） */
export async function verifyRoomToken(
  kv: KVNamespace | undefined,
  tokenB64: string | null | undefined,
  options?: RoomTokenVerifyOptions,
): Promise<{ routeId: string } | { error: string }> {
  if (!tokenB64) return { error: 'missing room token' };
  const tokenHash = await hashRoomToken(tokenB64);
  if (!tokenHash) return { error: 'invalid room token format' };
  return verifyRouteHash(kv, tokenHash, options);
}

/** 隧道帧鉴权：仅 routeHash，无 roomToken */
export async function verifyRouteHash(
  kv: KVNamespace | undefined,
  routeHash: Uint8Array,
  options?: RoomTokenVerifyOptions,
): Promise<{ routeId: string } | { error: string }> {
  if (routeHash.length !== LIMITS.roomTokenBytes) {
    return { error: 'invalid route hash length' };
  }
  const routeId = routeIdFromTokenHash(routeHash);
  const err = await verifyTokenHash(routeId, routeHash, kv, options);
  if (err) return { error: err };
  return { routeId };
}

export function buildSignPayload(fields: {
  routeId: string;
  senderId: string;
  ciphertext: string;
  iv: string;
  tag: string;
  timestamp: number;
}): Uint8Array {
  const line = [
    fields.routeId,
    fields.senderId,
    String(fields.timestamp),
    fields.ciphertext,
    fields.iv,
    fields.tag,
  ].join('\n');
  return new TextEncoder().encode(line);
}

export async function senderIdFromPublicKeyAsync(publicKey: Uint8Array): Promise<string> {
  const hash = await sha256(publicKey);
  const suffix = bytesToBase64(hash.subarray(0, 6))
    .replace(/\+/g, 'x')
    .replace(/\//g, 'y')
    .replace(/=/g, '');
  return `id_${suffix}`;
}

export async function verifyMessageSignature(fields: SignedMessageFields): Promise<string | null> {
  const { senderPk, signPk, sig, senderId, routeId, ciphertext, iv, tag } = fields;
  if (!senderPk || !signPk || !sig) {
    return 'missing senderPk, signPk, or sig';
  }
  const senderPkBytes = base64ToBytes(senderPk);
  const signPkBytes = base64ToBytes(signPk);
  const sigBytes = base64ToBytes(sig);
  if (!senderPkBytes || senderPkBytes.length !== 32) return 'invalid senderPk';
  if (!signPkBytes || signPkBytes.length !== 32) return 'invalid signPk';
  if (!sigBytes || sigBytes.length !== 64) return 'invalid sig';

  const expectedSenderId = await senderIdFromPublicKeyAsync(senderPkBytes);
  if (expectedSenderId !== senderId) return 'senderId does not match senderPk';

  const ts = fields.timestamp ?? Date.now();
  const payload = buildSignPayload({
    routeId,
    senderId,
    ciphertext,
    iv,
    tag,
    timestamp: ts,
  });

  try {
    const key = await crypto.subtle.importKey('raw', signPkBytes, { name: 'Ed25519' }, false, [
      'verify',
    ]);
    const ok = await crypto.subtle.verify('Ed25519', key, sigBytes, payload);
    if (!ok) return 'invalid message signature';
    return null;
  } catch {
    return 'signature verification failed';
  }
}

export function getClientIp(request: Request): string {
  return (
    request.headers.get('CF-Connecting-IP') ||
    request.headers.get('X-Forwarded-For')?.split(',')[0]?.trim() ||
    'unknown'
  );
}
