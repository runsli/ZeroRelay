import { b64decode, b64encode, b64urlDecode, b64urlEncode, utf8 } from './bytes';
import { contactIdFromPublicKey } from './identity';

export interface ParsedContact {
  publicKey: string;
  name: string | null;
}

export function encodeContactPayload(publicKeyB64: string, name?: string): string {
  const json = JSON.stringify({ v: 1, pk: publicKeyB64, ...(name ? { n: name } : {}) });
  return `zerorelay://v1?d=${b64urlEncode(utf8(json))}`;
}

export async function parseContactPayload(raw: string): Promise<ParsedContact> {
  const trimmed = raw.trim();
  let jsonStr: string;
  if (trimmed.startsWith('zerorelay://v1?d=')) {
    jsonStr = new TextDecoder().decode(b64urlDecode(trimmed.slice('zerorelay://v1?d='.length)));
  } else if (trimmed.startsWith('{')) {
    jsonStr = trimmed;
  } else {
    const key = b64decode(trimmed);
    if (key.length !== 32) throw new Error('公钥无效');
    return { publicKey: b64encode(key), name: null };
  }
  const json = JSON.parse(jsonStr) as { v?: number; pk?: string; n?: string };
  if (json.v !== 1 || !json.pk) throw new Error('二维码无效');
  const key = b64decode(json.pk);
  if (key.length !== 32) throw new Error('公钥无效');
  return { publicKey: json.pk, name: json.n || null };
}

export async function contactDisplayName(publicKey: string, name: string | null): Promise<string> {
  if (name) return name;
  const { fingerprint } = await import('./identity');
  return fingerprint(publicKey);
}

export { contactIdFromPublicKey };
