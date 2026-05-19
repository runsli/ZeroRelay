import { routeIdFromToken, verifyMessage, type RelayMessage } from './relay-crypto';
import { ratchetDecrypt, type RatchetStore } from './ratchet';

export interface InboundLine {
  text: string;
  timestamp: number;
  senderId: string;
}

export function createInboundProcessor(opts: {
  seenIds: Set<string>;
  roomId: string;
  roomAccessToken: string;
  sessionKey: Uint8Array;
  localPublicKey: string;
  peerPublicKey: string;
  ratchetStore: RatchetStore;
  mySenderId: string;
}) {
  return async function ingest(msg: RelayMessage): Promise<InboundLine | null> {
    const id = msg.id || `${msg.senderId}-${msg.timestamp}`;
    if (opts.seenIds.has(id)) return null;
    opts.seenIds.add(id);

    const routeId = await routeIdFromToken(opts.roomAccessToken);
    if (!routeId) return null;
    if (!verifyMessage({ ...msg, routeId })) return null;

    const decrypted = await ratchetDecrypt(
      opts.ratchetStore,
      opts.roomId,
      opts.sessionKey,
      opts.localPublicKey,
      opts.peerPublicKey,
      msg.ciphertext,
      msg.iv,
      msg.tag,
      msg.timestamp || 0,
    );
    if (!decrypted?.text) return null;

    return {
      text: decrypted.text,
      timestamp: msg.timestamp || 0,
      senderId: msg.senderId,
    };
  };
}
