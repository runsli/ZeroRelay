/**
 * 入站消息解密与去重（HTTP 轮询与 WebSocket 共用）
 */
const relayCrypto = require('./cli-relay-crypto');

/**
 * @param {object} ctx
 * @param {Set<string>} ctx.seenIds
 * @param {(senderId: string) => string} ctx.formatPrefix
 * @param {boolean} ctx.isGroupChat
 * @param {Map<number, Buffer>|null} ctx.groupKeys
 * @param {string} ctx.roomId
 * @param {string} ctx.roomAccessToken
 * @param {Buffer} ctx.sessionKey
 * @param {string|null} ctx.localPublicKey
 * @param {string|null} ctx.peerPublicKey
 * @param {object} ctx.protocol
 * @param {object} ctx.deps - ratchetDecrypt, decryptGroupMessage
 * @param {{ value: boolean }} ctx.legacyFlag - mutable { value: boolean }
 */
function createInboundProcessor(ctx) {
  return function ingest(msg) {
    const id = msg.id || `${msg.senderId}-${msg.timestamp}`;
    if (ctx.seenIds.has(id)) return null;
    ctx.seenIds.add(id);
    const routeId = relayCrypto.routeIdFromToken(ctx.roomAccessToken);
    if (!routeId) return null;
    if (!relayCrypto.verifyMessage({ ...msg, routeId })) return null;

    try {
      let text = null;
      let usedLegacy = false;
      if (ctx.isGroupChat) {
        text = ctx.deps.decryptGroupMessage(
          msg.ciphertext,
          msg.iv,
          msg.tag,
          ctx.groupKeys,
          msg.senderId,
        );
      } else {
        const decrypted = ctx.deps.ratchetDecrypt(
          ctx.roomId,
          ctx.sessionKey,
          ctx.localPublicKey,
          ctx.peerPublicKey,
          msg.ciphertext,
          msg.iv,
          msg.tag,
          msg.senderId,
          msg.timestamp || 0,
        );
        if (decrypted?.usedLegacyStaticKey) usedLegacy = true;
        text = decrypted?.text ?? null;
      }
      if (text == null) return null;

      if (usedLegacy && !ctx.legacyFlag.value) {
        ctx.legacyFlag.value = true;
        const cutoff = new Date(ctx.protocol.LEGACY_STATIC_CUTOFF_MS).toISOString().slice(0, 10);
        return {
          line: `[!] 部分消息使用旧版兼容解密（${cutoff} 后不再支持）`,
          timestamp: msg.timestamp || 0,
        };
      }

      const time = new Date(msg.timestamp).toLocaleTimeString();
      return {
        line: `${time} ${ctx.formatPrefix(msg.senderId)}: ${text}`,
        timestamp: msg.timestamp || 0,
      };
    } catch {
      return null;
    }
  };
}

module.exports = { createInboundProcessor };
