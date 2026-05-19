import { createInboundProcessor } from '../crypto/inbound';
import { deriveRoomAccessToken, deriveSigningKeys, routeIdFromToken, senderIdFromPublicKey, signMessage } from '../crypto/relay-crypto';
import { ratchetEncrypt } from '../crypto/ratchet';
import { deriveRoomId, deriveSessionKey, type Identity } from '../crypto/identity';
import { POLL_TIMEOUT_MS, POLL_WS_CONNECTED_MS, notePollResult, pollDelayMs } from '../crypto/protocol';
import { pollMessages, sendMessage } from '../relay/relay-client';
import { RelayWebSocket } from '../relay/relay-ws';
import type { Contact } from '../store/app-store';
import { createRatchetStore, effectiveServerUrl } from '../store/app-store';

export interface ChatMessage {
  id: string;
  text: string;
  outgoing: boolean;
  timestamp: number;
}

export class DirectChatSession {
  private polling = true;
  private lastTimestamp = 0;
  private readonly seenIds = new Set<string>();
  private relayWs: RelayWebSocket | null = null;
  private readonly messages: ChatMessage[] = [];
  private onUpdate: (() => void) | null = null;

  readonly roomId: string;
  readonly sessionKey: Uint8Array;
  readonly roomAccessToken: string;
  readonly senderId: string;
  readonly signingKeys: Awaited<ReturnType<typeof deriveSigningKeys>>;
  private readonly serverUrl: string;

  private constructor(
    readonly identity: Identity,
    readonly contact: Contact,
    opts: {
      roomId: string;
      sessionKey: Uint8Array;
      roomAccessToken: string;
      senderId: string;
      signingKeys: Awaited<ReturnType<typeof deriveSigningKeys>>;
      serverUrl: string;
    },
  ) {
    this.roomId = opts.roomId;
    this.sessionKey = opts.sessionKey;
    this.roomAccessToken = opts.roomAccessToken;
    this.senderId = opts.senderId;
    this.signingKeys = opts.signingKeys;
    this.serverUrl = opts.serverUrl;
  }

  static async open(identity: Identity, contact: Contact, serverUrlRaw: string): Promise<DirectChatSession> {
    const serverUrl = effectiveServerUrl(serverUrlRaw);
    const roomId = await deriveRoomId(identity.publicKey, contact.publicKey);
    const sessionKey = await deriveSessionKey(identity.privateKey, contact.publicKey, roomId);
    const senderId = await senderIdFromPublicKey(identity.publicKey);
    const signingKeys = await deriveSigningKeys(identity.privateKey);
    await senderIdFromPublicKey(identity.publicKey);
    await senderIdFromPublicKey(contact.publicKey);
    const roomAccessToken = await deriveRoomAccessToken(sessionKey, roomId);
    return new DirectChatSession(identity, contact, {
      roomId,
      sessionKey,
      roomAccessToken,
      senderId,
      signingKeys,
      serverUrl,
    });
  }

  getMessages(): ChatMessage[] {
    return this.messages;
  }

  setOnUpdate(fn: () => void): void {
    this.onUpdate = fn;
  }

  async start(): Promise<void> {
    const ratchetStore = createRatchetStore();
    const ingest = createInboundProcessor({
      seenIds: this.seenIds,
      roomId: this.roomId,
      roomAccessToken: this.roomAccessToken,
      sessionKey: this.sessionKey,
      localPublicKey: this.identity.publicKey,
      peerPublicKey: this.contact.publicKey,
      ratchetStore,
      mySenderId: this.senderId,
    });

    const applyInbound = async (msg: { id?: string; senderId: string; timestamp: number; ciphertext: string; iv: string; tag: string }) => {
      const out = await ingest(msg);
      if (!out) return;
      this.messages.push({
        id: msg.id || `${msg.senderId}-${msg.timestamp}`,
        text: out.text,
        outgoing: msg.senderId === this.senderId,
        timestamp: out.timestamp,
      });
      this.lastTimestamp = Math.max(this.lastTimestamp, out.timestamp);
      this.onUpdate?.();
    };

    this.relayWs = new RelayWebSocket({
      serverUrl: this.serverUrl,
      roomAccessToken: this.roomAccessToken,
      senderId: this.senderId,
      onMessage: (m) => void applyInbound(m),
    });
    this.relayWs.connect();

    const pollLoop = async () => {
      while (this.polling) {
        try {
          const msgs = await pollMessages(
            this.serverUrl,
            this.roomAccessToken,
            this.lastTimestamp,
            POLL_TIMEOUT_MS,
          );
          const got = msgs.length > 0;
          if (got) {
            for (const m of msgs) await applyInbound(m);
          }
          notePollResult(got);
        } catch {
          notePollResult(false);
        }
        const delay = this.relayWs?.isConnected() ? POLL_WS_CONNECTED_MS : pollDelayMs();
        await new Promise((r) => setTimeout(r, delay));
      }
    };
    void pollLoop();
  }

  async sendText(text: string): Promise<void> {
    if (!this.contact.verified) {
      throw new Error('请先核对安全码并标记为已验证');
    }
    const payload = await ratchetEncrypt(
      createRatchetStore(),
      this.roomId,
      this.sessionKey,
      this.identity.publicKey,
      this.contact.publicKey,
      text,
    );
    const now = Date.now();
    const localId = `local-${now}`;
    this.seenIds.add(localId);
    this.messages.push({ id: localId, text, outgoing: true, timestamp: now });
    this.onUpdate?.();

    const routeId = await routeIdFromToken(this.roomAccessToken);
    if (!routeId) throw new Error('invalid room token');

    const ok = await sendMessage(this.serverUrl, this.roomAccessToken, {
      op: 'send',
      ciphertext: payload.ciphertext,
      iv: payload.iv,
      tag: payload.tag,
      senderPk: this.identity.publicKey,
      signPk: this.signingKeys.signPublic,
      sig: signMessage(
        this.signingKeys,
        routeId,
        this.senderId,
        now,
        payload.ciphertext,
        payload.iv,
        payload.tag,
      ),
      timestamp: now,
    });
    if (!ok) throw new Error('发送失败');
  }

  stop(): void {
    this.polling = false;
    this.relayWs?.close();
    this.relayWs = null;
  }
}
