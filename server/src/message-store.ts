/**
 * 消息存储抽象：本地内存 / Cloudflare KV（按条写入，避免并发覆盖丢消息）
 */

import { resolveStorePolicy, type StorePolicy, type StorePolicyEnv } from './store-policy';

export interface Message {
  id: string;
  roomId: string;
  ciphertext: string;
  iv: string;
  tag: string;
  timestamp: number;
  senderId: string;
  senderPk: string;
  signPk: string;
  sig: string;
}

export interface MessageStore {
  addMessage(roomId: string, message: Message): Promise<void>;
  getMessagesSince(roomId: string, since: number): Promise<Message[]>;
  getMessageCount(roomId: string): Promise<number>;
}

interface RoomMeta {
  count: number;
  lastTs: number;
}

function messageKey(roomId: string, message: Message): string {
  const ts = String(message.timestamp).padStart(13, '0');
  return `room:${roomId}:msg:${ts}:${message.id}`;
}

const LEGACY_SUFFIX = ':messages';
const META_SUFFIX = ':meta';

function metaKey(roomId: string): string {
  return `room:${roomId}${META_SUFFIX}`;
}

function legacyKey(roomId: string): string {
  return `room:${roomId}${LEGACY_SUFFIX}`;
}

/** KV 存储省略恒空 roomId，减少元数据体积 */
function messageToKvJson(message: Message): string {
  if (!message.roomId) {
    const { roomId: _omit, ...rest } = message;
    return JSON.stringify(rest);
  }
  return JSON.stringify(message);
}

export class MemoryMessageStore implements MessageStore {
  private messages = new Map<string, Message[]>();
  private meta = new Map<string, RoomMeta>();

  constructor(private readonly policy: StorePolicy) {}

  async addMessage(roomId: string, message: Message): Promise<void> {
    if (!this.messages.has(roomId)) {
      this.messages.set(roomId, []);
    }
    const list = this.messages.get(roomId)!;
    list.push(message);
    const max = this.policy.maxMessagesPerRoom;
    if (list.length > max) {
      this.messages.set(roomId, list.slice(-max));
    }
    const trimmed = this.messages.get(roomId)!;
    this.meta.set(roomId, {
      count: trimmed.length,
      lastTs: trimmed[trimmed.length - 1]?.timestamp ?? message.timestamp,
    });
  }

  async getMessagesSince(roomId: string, since: number): Promise<Message[]> {
    const roomMeta = this.meta.get(roomId);
    if (roomMeta && since >= roomMeta.lastTs) {
      return [];
    }
    const list = this.messages.get(roomId) || [];
    return list.filter((m) => m.timestamp > since);
  }

  async getMessageCount(roomId: string): Promise<number> {
    const roomMeta = this.meta.get(roomId);
    if (roomMeta) return roomMeta.count;
    return (this.messages.get(roomId) || []).length;
  }
}

export class KvMessageStore implements MessageStore {
  constructor(
    private kv: KVNamespace,
    private readonly policy: StorePolicy,
  ) {}

  async addMessage(roomId: string, message: Message): Promise<void> {
    const ttl = this.policy.messageTtlSec;
    await this.kv.put(messageKey(roomId, message), messageToKvJson(message), {
      expirationTtl: ttl,
    });
    const current = (await this.readMeta(roomId)) ?? { count: 0, lastTs: 0 };
    current.count += 1;
    current.lastTs = Math.max(current.lastTs, message.timestamp);
    await this.writeMeta(roomId, current);
    await this.trimRoom(roomId);
  }

  async getMessagesSince(roomId: string, since: number): Promise<Message[]> {
    const roomMeta = await this.readMeta(roomId);
    if (roomMeta && since >= roomMeta.lastTs) {
      const legacy = await this.kv.get(legacyKey(roomId));
      if (!legacy) return [];
    }

    const fromKeys = await this.loadFromPerMessageKeys(roomId, since);
    const fromLegacy = await this.loadFromLegacyBlob(roomId, since);
    const merged = new Map<string, Message>();
    for (const msg of [...fromLegacy, ...fromKeys]) {
      if (msg.timestamp > since) {
        merged.set(msg.id, msg);
      }
    }
    return [...merged.values()].sort((a, b) => a.timestamp - b.timestamp);
  }

  async getMessageCount(roomId: string): Promise<number> {
    const roomMeta = await this.readMeta(roomId);
    const legacy = await this.kv.get(legacyKey(roomId));
    if (roomMeta && !legacy) {
      return roomMeta.count;
    }
    return this.countAllMessages(roomId);
  }

  private async readMeta(roomId: string): Promise<RoomMeta | null> {
    const raw = await this.kv.get(metaKey(roomId));
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as RoomMeta;
      if (
        typeof parsed.count === 'number' &&
        typeof parsed.lastTs === 'number' &&
        Number.isFinite(parsed.count) &&
        Number.isFinite(parsed.lastTs)
      ) {
        return parsed;
      }
    } catch {
      /* ignore */
    }
    return null;
  }

  private async writeMeta(roomId: string, meta: RoomMeta): Promise<void> {
    await this.kv.put(metaKey(roomId), JSON.stringify(meta), {
      expirationTtl: this.policy.messageTtlSec,
    });
  }

  /** Drop oldest per-message keys when a room exceeds the cap. */
  private async trimRoom(roomId: string): Promise<void> {
    const max = this.policy.maxMessagesPerRoom;
    const prefix = `room:${roomId}:msg:`;
    const keys: string[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.kv.list({ prefix, cursor });
      for (const entry of page.keys) {
        keys.push(entry.name);
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);

    if (keys.length <= max) return;

    keys.sort();
    const toDelete = keys.slice(0, keys.length - max);
    await Promise.all(toDelete.map((name) => this.kv.delete(name)));

    let lastTs = 0;
    for (const name of keys.slice(-max)) {
      const match = name.match(/:msg:(\d{13}):/);
      if (match) lastTs = Math.max(lastTs, parseInt(match[1], 10));
    }
    await this.writeMeta(roomId, { count: max, lastTs });
  }

  private async countAllMessages(roomId: string): Promise<number> {
    const prefix = `room:${roomId}:msg:`;
    let total = 0;
    let lastTs = 0;
    let cursor: string | undefined;
    do {
      const page = await this.kv.list({ prefix, cursor });
      total += page.keys.length;
      for (const entry of page.keys) {
        const match = entry.name.match(/:msg:(\d{13}):/);
        if (match) {
          const ts = parseInt(match[1], 10);
          if (ts > lastTs) lastTs = ts;
        }
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);

    const legacy = await this.kv.get(legacyKey(roomId));
    if (legacy) {
      try {
        const list = JSON.parse(legacy) as Message[];
        total += list.length;
        for (const msg of list) {
          if (msg.timestamp > lastTs) lastTs = msg.timestamp;
        }
      } catch {
        /* ignore */
      }
    }

    if (total > 0) {
      await this.writeMeta(roomId, { count: total, lastTs });
    }

    return total;
  }

  private async loadFromPerMessageKeys(
    roomId: string,
    since: number,
  ): Promise<Message[]> {
    const prefix = `room:${roomId}:msg:`;
    const out: Message[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.kv.list({ prefix, cursor });
      for (const entry of page.keys) {
        const raw = await this.kv.get(entry.name);
        if (!raw) continue;
        try {
          const msg = JSON.parse(raw) as Message;
          if (msg.timestamp > since) {
            out.push(msg);
          }
        } catch {
          /* ignore corrupt entry */
        }
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
    return out;
  }

  private async loadFromLegacyBlob(
    roomId: string,
    since: number,
  ): Promise<Message[]> {
    const raw = await this.kv.get(legacyKey(roomId));
    if (!raw) return [];
    try {
      return (JSON.parse(raw) as Message[]).filter((m) => m.timestamp > since);
    } catch {
      return [];
    }
  }
}

export function createMessageStore(
  env: { MESSAGE_KV?: KVNamespace } & StorePolicyEnv,
): MessageStore {
  const policy = resolveStorePolicy(env);
  if (env.MESSAGE_KV) {
    return new KvMessageStore(env.MESSAGE_KV, policy);
  }
  return new MemoryMessageStore(policy);
}
