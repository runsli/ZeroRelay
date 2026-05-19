import { b64decode, b64encode, randomBytes, utf8, utf8Decode } from './bytes';

export const PROTOCOL_V2 = 2;
export const LEGACY_STATIC_CUTOFF_MS = 1_767_225_600_000;
export const POLL_TIMEOUT_MS = 8000;
export const POLL_WS_CONNECTED_MS = 30000;
export const HKDF_INFO = 'zero-relay-v1';

const PADDING_BLOCK = 256;
let pollBackoffMs = 1000;
const POLL_MAX_BACKOFF_MS = 15000;
const POLL_JITTER_MS = 500;

export function allowsLegacyStaticFallback(messageTimestamp: number): boolean {
  return messageTimestamp < LEGACY_STATIC_CUTOFF_MS;
}

export function pollDelayMs(): number {
  return pollBackoffMs + Math.floor(Math.random() * (POLL_JITTER_MS + 1));
}

export function notePollResult(gotMessages: boolean): void {
  if (gotMessages) pollBackoffMs = 1000;
  else pollBackoffMs = Math.min(Math.floor((pollBackoffMs * 3) / 2), POLL_MAX_BACKOFF_MS);
}

export function padPlaintext(plaintext: string): string {
  const content = utf8(plaintext);
  if (content.length > PADDING_BLOCK - 5) throw new Error('消息过长');
  const totalBlocks = Math.max(1, Math.ceil((content.length + 5) / PADDING_BLOCK));
  const out = new Uint8Array(totalBlocks * PADDING_BLOCK);
  out[0] = 0x01;
  new DataView(out.buffer).setUint32(1, content.length, false);
  out.set(content, 5);
  if (out.length > 5 + content.length) {
    const tail = randomBytes(out.length - 5 - content.length);
    out.set(tail, 5 + content.length);
  }
  return b64encode(out);
}

export function unpadPlaintext(paddedB64: string): string | null {
  try {
    const buf = b64decode(paddedB64);
    if (!buf.length || buf[0] !== 0x01) return null;
    const len = new DataView(buf.buffer, buf.byteOffset, buf.byteLength).getUint32(1, false);
    if (len < 0 || 5 + len > buf.length) return null;
    return utf8Decode(buf.subarray(5, 5 + len));
  } catch {
    return null;
  }
}

export function unwrapPlaintext(maybePadded: string): string {
  return unpadPlaintext(maybePadded) ?? maybePadded;
}
