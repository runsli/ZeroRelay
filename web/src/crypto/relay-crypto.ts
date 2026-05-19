import { ed25519 } from '@noble/curves/ed25519';
import { b64decode, b64encode, b64urlEncode, sha256, utf8 } from './bytes';
import { hkdfSha256 } from './hkdf';

const ROOM_ACCESS_INFO = 'zero-relay-room-access-v1';
const SIGN_HKDF_SALT = 'zero-relay-sign-v1';
const SIGN_SEED_INFO = 'ed25519-seed';

export interface SigningKeys {
  signPrivate: Uint8Array;
  signPublic: string;
}

export interface RelayMessage {
  id?: string;
  senderId: string;
  senderPk?: string;
  signPk?: string;
  sig?: string;
  ciphertext: string;
  iv: string;
  tag: string;
  timestamp: number;
}

const senderIdCache = new Map<string, string>();

export async function deriveRoomAccessToken(roomSecret: Uint8Array, roomId: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    roomSecret,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const msg = utf8(ROOM_ACCESS_INFO + roomId);
  const sig = await crypto.subtle.sign('HMAC', key, msg);
  return b64encode(new Uint8Array(sig));
}

export async function routeHashFromToken(tokenB64: string): Promise<Uint8Array | null> {
  try {
    const raw = b64decode(tokenB64);
    if (raw.length !== 32) return null;
    return sha256(raw);
  } catch {
    return null;
  }
}

export async function routeIdFromToken(tokenB64: string): Promise<string | null> {
  const hash = await routeHashFromToken(tokenB64);
  return hash ? b64urlEncode(hash) : null;
}

export async function deriveSigningKeys(identityPrivateKeyB64: string): Promise<SigningKeys> {
  const seed = await hkdfSha256(
    b64decode(identityPrivateKeyB64),
    utf8(SIGN_HKDF_SALT),
    SIGN_SEED_INFO,
  );
  return { signPrivate: seed, signPublic: b64encode(ed25519.getPublicKey(seed)) };
}

function buildSignPayload(
  routeId: string,
  senderId: string,
  timestamp: number,
  ciphertext: string,
  iv: string,
  tag: string,
): Uint8Array {
  return utf8([routeId, senderId, String(timestamp), ciphertext, iv, tag].join('\n'));
}

export function signMessage(
  keys: SigningKeys,
  routeId: string,
  senderId: string,
  timestamp: number,
  ciphertext: string,
  iv: string,
  tag: string,
): string {
  return b64encode(
    ed25519.sign(buildSignPayload(routeId, senderId, timestamp, ciphertext, iv, tag), keys.signPrivate),
  );
}

export async function senderIdFromPublicKey(pubB64: string): Promise<string> {
  const cached = senderIdCache.get(pubB64);
  if (cached) return cached;
  const hash = await sha256(b64decode(pubB64));
  const id =
    'id_' +
    b64urlEncode(hash.subarray(0, 6))
      .replace(/-/g, 'x')
      .replace(/_/g, 'y');
  senderIdCache.set(pubB64, id);
  return id;
}

export function verifyMessage(msg: RelayMessage & { routeId: string }): boolean {
  const { senderPk, signPk, sig, senderId, routeId, ciphertext, iv, tag, timestamp } = msg;
  if (!senderPk || !signPk || !sig) return false;
  const cached = senderIdCache.get(senderPk);
  if (cached && cached !== senderId) return false;
  const payload = buildSignPayload(routeId, senderId, timestamp, ciphertext, iv, tag);
  try {
    return ed25519.verify(b64decode(sig), payload, b64decode(signPk));
  } catch {
    return false;
  }
}
