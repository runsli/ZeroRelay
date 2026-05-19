/**
 * 中继全隧道 v2：帧内仅 routeHash(32)，无 roomToken；AES 密钥 = HKDF(routeHash)。
 * 帧布局: version(1) | routeHash(32) | iv(12) | ciphertext+tag
 */

import { base64ToBytes, bytesToBase64, routeIdFromTokenHash } from './relay-security';

export const TUNNEL_VERSION = 0x02;
export const TUNNEL_INFO = 'zero-relay-tunnel-v1';
export const TUNNEL_HEADER_LEN = 1 + 32 + 12;

const TUNNEL_PAD_BUCKETS = [256, 512, 1024, 2048];

/** 内层 JSON 填充到固定档位（字段 `_p` 仅用于 padding，服务端忽略） */
export function padTunnelInner(inner: TunnelInner): TunnelInner & { _p?: string } {
  const out = { ...inner } as TunnelInner & { _p?: string };
  for (const target of TUNNEL_PAD_BUCKETS) {
    let json = JSON.stringify(out);
    if (json.length >= target) return out;
    const need = target - json.length - 12;
    if (need <= 0) continue;
    const pad = new Uint8Array(Math.min(need, target));
    crypto.getRandomValues(pad);
    out._p = bytesToBase64(pad);
    json = JSON.stringify(out);
    if (json.length <= target) return out;
  }
  return out;
}

export interface TunnelSendInner {
  op: 'send';
  ciphertext: string;
  iv: string;
  tag: string;
  senderPk: string;
  signPk: string;
  sig: string;
  timestamp: number;
}

export interface TunnelPollInner {
  op: 'poll';
  since: number;
  timeout: number;
}

export type TunnelInner = TunnelSendInner | TunnelPollInner;

async function tunnelKeyFromRouteHash(routeHash: Uint8Array): Promise<CryptoKey> {
  const ikm = await crypto.subtle.importKey('raw', routeHash, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: new TextEncoder().encode(TUNNEL_INFO) },
    ikm,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

export async function routeHashFromTokenB64(
  tokenB64: string,
): Promise<{ routeHash: Uint8Array; routeId: string } | { error: string }> {
  const tokenRaw = base64ToBytes(tokenB64);
  if (!tokenRaw || tokenRaw.length !== 32) return { error: 'invalid room token' };
  const routeHash = new Uint8Array(await crypto.subtle.digest('SHA-256', tokenRaw));
  return { routeHash, routeId: routeIdFromTokenHash(routeHash) };
}

export async function encodeTunnelFrame(
  tokenB64: string,
  inner: TunnelInner,
): Promise<{ frame: Uint8Array; routeId: string } | { error: string }> {
  const derived = await routeHashFromTokenB64(tokenB64);
  if ('error' in derived) return derived;
  const { routeHash, routeId } = derived;
  const key = await tunnelKeyFromRouteHash(routeHash);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = new TextEncoder().encode(JSON.stringify(padTunnelInner(inner)));
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plain),
  );
  const frame = new Uint8Array(TUNNEL_HEADER_LEN + ct.length);
  frame[0] = TUNNEL_VERSION;
  frame.set(routeHash, 1);
  frame.set(iv, 33);
  frame.set(ct, 45);
  return { frame, routeId };
}

export async function decodeTunnelFrame(
  body: ArrayBuffer,
): Promise<
  | { routeId: string; routeHash: Uint8Array; inner: TunnelInner }
  | { error: string }
> {
  const buf = new Uint8Array(body);
  if (buf.length < TUNNEL_HEADER_LEN + 16) return { error: 'tunnel frame too short' };
  if (buf[0] !== TUNNEL_VERSION) return { error: 'unsupported tunnel version' };

  const routeHash = buf.subarray(1, 33);
  const iv = buf.subarray(33, 45);
  const ct = buf.subarray(45);

  const key = await tunnelKeyFromRouteHash(routeHash);
  let plain: ArrayBuffer;
  try {
    plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ct);
  } catch {
    return { error: 'tunnel decrypt failed' };
  }

  let inner: TunnelInner;
  try {
    inner = JSON.parse(new TextDecoder().decode(plain)) as TunnelInner;
  } catch {
    return { error: 'invalid tunnel payload' };
  }
  if (inner?.op !== 'send' && inner?.op !== 'poll') {
    return { error: 'invalid tunnel op' };
  }

  return {
    routeId: routeIdFromTokenHash(routeHash),
    routeHash,
    inner,
  };
}
