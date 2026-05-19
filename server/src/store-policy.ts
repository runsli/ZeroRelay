export const DEFAULT_MESSAGE_TTL_SEC = 7_200; // 2 hours
export const DEFAULT_MAX_MESSAGES_PER_ROOM = 100;

export interface StorePolicy {
  messageTtlSec: number;
  maxMessagesPerRoom: number;
}

export interface StorePolicyEnv {
  MESSAGE_TTL_SEC?: string;
  MAX_MESSAGES_PER_ROOM?: string;
}

export function resolveStorePolicy(env?: StorePolicyEnv): StorePolicy {
  const ttl = parseInt(env?.MESSAGE_TTL_SEC ?? '', 10);
  const max = parseInt(env?.MAX_MESSAGES_PER_ROOM ?? '', 10);
  return {
    messageTtlSec:
      Number.isFinite(ttl) && ttl >= 60 ? ttl : DEFAULT_MESSAGE_TTL_SEC,
    maxMessagesPerRoom:
      Number.isFinite(max) && max >= 10 ? max : DEFAULT_MAX_MESSAGES_PER_ROOM,
  };
}
