/**
 * 协议策略（与 Android ProtocolPolicy / MessagePadding 对齐）
 */
const crypto = require('crypto');

const PROTOCOL_V2 = 2;
const LEGACY_STATIC_CUTOFF_MS = 1_767_225_600_000; // 2026-01-01T00:00:00Z
const POLL_TIMEOUT_MS = 8000;
const POLL_BASE_MS = 1000;
const POLL_JITTER_MS = 500;
const POLL_MAX_BACKOFF_MS = 15000;
/** WebSocket 已连接时 HTTP 轮询间隔（毫秒） */
const POLL_WS_CONNECTED_MS = 30000;
const PADDING_BLOCK = 256;

function allowsLegacyStaticFallback(messageTimestamp) {
  return messageTimestamp < LEGACY_STATIC_CUTOFF_MS;
}

let pollBackoffMs = POLL_BASE_MS;

function pollDelayMs() {
  return pollBackoffMs + Math.floor(Math.random() * (POLL_JITTER_MS + 1));
}

function notePollResult(gotMessages) {
  if (gotMessages) {
    pollBackoffMs = POLL_BASE_MS;
  } else {
    pollBackoffMs = Math.min(Math.floor((pollBackoffMs * 3) / 2), POLL_MAX_BACKOFF_MS);
  }
}

function padPlaintext(plaintext) {
  const content = Buffer.from(plaintext, 'utf8');
  if (content.length > PADDING_BLOCK - 5) throw new Error('消息过长');
  const totalBlocks = Math.max(1, Math.ceil((content.length + 5) / PADDING_BLOCK));
  const out = Buffer.alloc(totalBlocks * PADDING_BLOCK);
  out[0] = 0x01;
  out.writeUInt32BE(content.length, 1);
  content.copy(out, 5);
  if (out.length > 5 + content.length) {
    crypto.randomFillSync(out, 5 + content.length, out.length - 5 - content.length);
  }
  return out.toString('base64');
}

function unpadPlaintext(paddedB64) {
  try {
    const buf = Buffer.from(paddedB64, 'base64');
    if (!buf.length || buf[0] !== 0x01) return null;
    const len = buf.readUInt32BE(1);
    if (len < 0 || 5 + len > buf.length) return null;
    return buf.subarray(5, 5 + len).toString('utf8');
  } catch {
    return null;
  }
}

function unwrapPlaintext(maybePadded) {
  return unpadPlaintext(maybePadded) ?? maybePadded;
}

module.exports = {
  PROTOCOL_V2,
  LEGACY_STATIC_CUTOFF_MS,
  allowsLegacyStaticFallback,
  POLL_TIMEOUT_MS,
  POLL_WS_CONNECTED_MS,
  pollDelayMs,
  notePollResult,
  padPlaintext,
  unwrapPlaintext,
};
