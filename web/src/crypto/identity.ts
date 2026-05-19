import { x25519 } from '@noble/curves/ed25519';
import { b64decode, b64encode, b64urlEncode, compareBytes, concat, sha256, utf8 } from './bytes';
import { hkdfSha256 } from './hkdf';
import { HKDF_INFO } from './protocol';

export interface Identity {
  publicKey: string;
  privateKey: string;
}

export function createIdentity(): Identity {
  const privateKey = x25519.utils.randomPrivateKey();
  return {
    publicKey: b64encode(x25519.getPublicKey(privateKey)),
    privateKey: b64encode(privateKey),
  };
}

export async function deriveRoomId(pubB64A: string, pubB64B: string): Promise<string> {
  const a = b64decode(pubB64A);
  const b = b64decode(pubB64B);
  const sorted = compareBytes(a, b) <= 0 ? [a, b] : [b, a];
  const hash = await sha256(concat(sorted[0]!, sorted[1]!));
  return b64urlEncode(hash);
}

export async function deriveSessionKey(
  privateKeyB64: string,
  peerPublicKeyB64: string,
  roomId: string,
): Promise<Uint8Array> {
  const privateKey = b64decode(privateKeyB64);
  const peerPublic = b64decode(peerPublicKeyB64);
  if (privateKey.length !== 32 || peerPublic.length !== 32) {
    throw new Error('密钥长度无效');
  }
  const shared = x25519.getSharedSecret(privateKey, peerPublic);
  return hkdfSha256(shared, utf8(roomId), HKDF_INFO);
}

export async function contactIdFromPublicKey(pubB64: string): Promise<string> {
  const hash = await sha256(b64decode(pubB64));
  return Array.from(hash.subarray(0, 8))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export async function fingerprint(pubB64: string): Promise<string> {
  const hash = await sha256(b64decode(pubB64));
  return Array.from(hash.subarray(0, 8))
    .map((b) => b.toString(16).padStart(2, '0').toUpperCase())
    .join('-');
}
