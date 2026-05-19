/**
 * 中继全隧道 v2（与 server/src/relay-tunnel.ts 帧布局一致）
 */
const crypto = require('crypto');
const relayCrypto = require('./cli-relay-crypto');

const TUNNEL_VERSION = 0x02;
const TUNNEL_INFO = 'zero-relay-tunnel-v1';
const TUNNEL_HEADER_LEN = 1 + 32 + 12;
const TUNNEL_PAD_BUCKETS = [256, 512, 1024, 2048];

function padTunnelInner(inner) {
  const out = { ...inner };
  for (const target of TUNNEL_PAD_BUCKETS) {
    let json = JSON.stringify(out);
    if (json.length >= target) return out;
    const need = target - json.length - 12;
    if (need <= 0) continue;
    out._p = crypto.randomBytes(Math.min(need, target)).toString('base64');
    json = JSON.stringify(out);
    if (json.length <= target) return out;
  }
  return out;
}

function routeHashFromToken(tokenB64) {
  const tokenRaw = Buffer.from(tokenB64, 'base64');
  if (tokenRaw.length !== 32) throw new Error('invalid room token');
  return crypto.createHash('sha256').update(tokenRaw).digest();
}

function tunnelKeyFromRouteHash(routeHash) {
  return crypto.hkdfSync('sha256', routeHash, Buffer.alloc(0), Buffer.from(TUNNEL_INFO, 'utf8'), 32);
}

function encodeTunnelFrame(tokenB64, inner) {
  const routeHash = routeHashFromToken(tokenB64);
  const routeId = relayCrypto.routeIdFromToken(tokenB64);
  const key = tunnelKeyFromRouteHash(routeHash);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const enc = Buffer.concat([cipher.update(JSON.stringify(padTunnelInner(inner)), 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  const ct = Buffer.concat([enc, tag]);
  const frame = Buffer.alloc(TUNNEL_HEADER_LEN + ct.length);
  frame[0] = TUNNEL_VERSION;
  routeHash.copy(frame, 1);
  iv.copy(frame, 33);
  ct.copy(frame, 45);
  return { frame, routeId };
}

function decodeTunnelFrame(buf) {
  if (!Buffer.isBuffer(buf)) buf = Buffer.from(buf);
  if (buf.length < TUNNEL_HEADER_LEN + 16) return { error: 'tunnel frame too short' };
  if (buf[0] !== TUNNEL_VERSION) return { error: 'unsupported tunnel version' };
  const routeHash = buf.subarray(1, 33);
  const iv = buf.subarray(33, 45);
  const ct = buf.subarray(45);
  const key = tunnelKeyFromRouteHash(routeHash);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  const tagLen = 16;
  decipher.setAuthTag(ct.subarray(ct.length - tagLen));
  const enc = ct.subarray(0, ct.length - tagLen);
  let plain;
  try {
    plain = Buffer.concat([decipher.update(enc), decipher.final()]).toString('utf8');
  } catch {
    return { error: 'tunnel decrypt failed' };
  }
  let inner;
  try {
    inner = JSON.parse(plain);
  } catch {
    return { error: 'invalid tunnel payload' };
  }
  if (inner.op !== 'send' && inner.op !== 'poll') return { error: 'invalid tunnel op' };
  return {
    routeId: routeHash.toString('base64url'),
    routeHash,
    inner,
  };
}

module.exports = {
  TUNNEL_VERSION,
  encodeTunnelFrame,
  decodeTunnelFrame,
};
