import { b64decode, b64encode, randomBytes, utf8 } from './bytes';
import { hkdfSha256 } from './hkdf';
import { routeIdFromToken } from './relay-crypto';

const TUNNEL_VERSION = 0x02;
const TUNNEL_INFO = 'zero-relay-tunnel-v1';
const TUNNEL_HEADER_LEN = 1 + 32 + 12;
const TUNNEL_PAD_BUCKETS = [256, 512, 1024, 2048];

export type TunnelInner =
  | { op: 'send'; ciphertext: string; iv: string; tag: string; senderPk: string; signPk: string; sig: string; timestamp: number; _p?: string }
  | { op: 'poll'; since: number; timeout: number; _p?: string };

function padTunnelInner(inner: TunnelInner): TunnelInner {
  const out = { ...inner } as TunnelInner & { _p?: string };
  for (const target of TUNNEL_PAD_BUCKETS) {
    let json = JSON.stringify(out);
    if (json.length >= target) return out;
    const need = target - json.length - 12;
    if (need <= 0) continue;
    out._p = b64encode(randomBytes(Math.min(need, target)));
    json = JSON.stringify(out);
    if (json.length <= target) return out;
  }
  return out;
}

async function routeHashFromTokenBytes(tokenB64: string): Promise<Uint8Array> {
  const raw = b64decode(tokenB64);
  if (raw.length !== 32) throw new Error('invalid room token');
  const { sha256: hashFn } = await import('./bytes');
  return hashFn(raw);
}

async function tunnelKeyFromRouteHash(routeHash: Uint8Array): Promise<Uint8Array> {
  return hkdfSha256(routeHash, new Uint8Array(0), TUNNEL_INFO);
}

export async function encodeTunnelFrame(
  tokenB64: string,
  inner: TunnelInner,
): Promise<{ frame: Uint8Array; routeId: string }> {
  const routeHash = await routeHashFromTokenBytes(tokenB64);
  const routeId = await routeIdFromToken(tokenB64);
  if (!routeId) throw new Error('invalid room token');
  const key = await tunnelKeyFromRouteHash(routeHash);
  const iv = randomBytes(12);
  const cryptoKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
  const plain = utf8(JSON.stringify(padTunnelInner(inner)));
  const enc = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, cryptoKey, plain));
  const tag = enc.slice(-16);
  const ciphertext = enc.slice(0, -16);
  const frame = new Uint8Array(TUNNEL_HEADER_LEN + ciphertext.length + tag.length);
  frame[0] = TUNNEL_VERSION;
  frame.set(routeHash, 1);
  frame.set(iv, 33);
  frame.set(ciphertext, 45);
  frame.set(tag, 45 + ciphertext.length);
  return { frame, routeId };
}
