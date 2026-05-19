import { b64decode, b64encode, utf8, utf8Decode } from './bytes';

export interface AesPayload {
  ciphertext: string;
  iv: string;
  tag: string;
}

export async function aesGcmEncrypt(plaintext: string, key: Uint8Array): Promise<AesPayload> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cryptoKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
  const enc = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, cryptoKey, utf8(plaintext));
  const combined = new Uint8Array(enc);
  const tag = combined.slice(-16);
  const ciphertext = combined.slice(0, -16);
  return {
    ciphertext: b64encode(ciphertext),
    iv: b64encode(iv),
    tag: b64encode(tag),
  };
}

export async function aesGcmDecrypt(
  ciphertext: string,
  iv: string,
  tag: string,
  key: Uint8Array,
): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt']);
  const ct = b64decode(ciphertext);
  const combined = new Uint8Array(ct.length + 16);
  combined.set(ct);
  combined.set(b64decode(tag), ct.length);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: b64decode(iv) },
    cryptoKey,
    combined,
  );
  return utf8Decode(new Uint8Array(plain));
}
