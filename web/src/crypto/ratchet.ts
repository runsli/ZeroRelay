import { b64decode, b64encode, compareBytes, utf8 } from './bytes';
import { aesGcmDecrypt, aesGcmEncrypt } from './aes';
import { hkdfSha256 } from './hkdf';
import {
  PROTOCOL_V2,
  allowsLegacyStaticFallback,
  padPlaintext,
  unwrapPlaintext,
} from './protocol';

export interface RatchetState {
  send: string;
  recv: string;
  ss: number;
  rs: number;
}

export type RatchetStore = {
  load(roomId: string): Promise<RatchetState | null>;
  save(roomId: string, state: RatchetState): Promise<void>;
};

function localIsLowerPublicKey(localPubB64: string, peerPubB64: string): boolean {
  return compareBytes(b64decode(localPubB64), b64decode(peerPubB64)) <= 0;
}

async function ratchetFromRoot(
  rootKey: Uint8Array,
  roomId: string,
  localPubB64: string,
  peerPubB64: string,
): Promise<RatchetState> {
  const salt = utf8(roomId);
  const lower = localIsLowerPublicKey(localPubB64, peerPubB64);
  const sendInfo = lower ? 'chain-send-v2' : 'chain-recv-v2';
  const recvInfo = lower ? 'chain-recv-v2' : 'chain-send-v2';
  return {
    send: b64encode(await hkdfSha256(rootKey, salt, sendInfo)),
    recv: b64encode(await hkdfSha256(rootKey, salt, recvInfo)),
    ss: 0,
    rs: 0,
  };
}

async function ratchetStepChain(chainB64: string): Promise<{ messageKey: Uint8Array; nextChain: string }> {
  const chainKey = b64decode(chainB64);
  return {
    messageKey: await hkdfSha256(chainKey, chainKey, 'message'),
    nextChain: b64encode(await hkdfSha256(chainKey, chainKey, 'chain')),
  };
}

function parseV2Envelope(plain: string): { v: number; s: number; t: string } | null {
  try {
    const json = JSON.parse(plain) as { v?: number; s?: number; t?: string };
    if (json.v === PROTOCOL_V2 && typeof json.t === 'string' && typeof json.s === 'number') {
      return { v: json.v, s: json.s, t: json.t };
    }
  } catch {
    /* legacy */
  }
  return null;
}

function parseLegacyPlain(plain: string): string | null {
  const env = parseV2Envelope(plain);
  if (env) return env.t;
  return plain;
}

export async function ratchetEncrypt(
  store: RatchetStore,
  roomId: string,
  rootKey: Uint8Array,
  localPubB64: string,
  peerPubB64: string,
  plaintext: string,
) {
  let state = (await store.load(roomId)) ?? (await ratchetFromRoot(rootKey, roomId, localPubB64, peerPubB64));
  const { messageKey, nextChain } = await ratchetStepChain(state.send);
  state.send = nextChain;
  const seq = state.ss;
  state.ss += 1;
  const envelope = JSON.stringify({ v: PROTOCOL_V2, s: seq, t: padPlaintext(plaintext) });
  const payload = await aesGcmEncrypt(envelope, messageKey);
  await store.save(roomId, state);
  return payload;
}

export async function ratchetDecrypt(
  store: RatchetStore,
  roomId: string,
  rootKey: Uint8Array,
  localPubB64: string,
  peerPubB64: string,
  ciphertext: string,
  iv: string,
  tag: string,
  messageTimestamp = 0,
): Promise<{ text: string; usedLegacyStaticKey: boolean } | null> {
  const ok = (text: string | null, usedLegacyStaticKey = false) =>
    text != null ? { text, usedLegacyStaticKey } : null;

  let state = (await store.load(roomId)) ?? (await ratchetFromRoot(rootKey, roomId, localPubB64, peerPubB64));
  const { messageKey, nextChain } = await ratchetStepChain(state.recv);
  let plain: string | null = null;
  try {
    plain = await aesGcmDecrypt(ciphertext, iv, tag, messageKey);
  } catch {
    plain = null;
  }
  if (plain != null) {
    const env = parseV2Envelope(plain);
    if (env) {
      const seq = env.s;
      if (seq < state.rs) {
        await store.save(roomId, state);
        return ok(unwrapPlaintext(env.t));
      }
      if (seq === state.rs) {
        state.recv = nextChain;
        state.rs += 1;
        await store.save(roomId, state);
        return ok(unwrapPlaintext(env.t));
      }
    } else {
      state.recv = nextChain;
      state.rs += 1;
      await store.save(roomId, state);
      return ok(unwrapPlaintext(plain));
    }
  }

  if (!allowsLegacyStaticFallback(messageTimestamp)) return null;
  try {
    const legacyPlain = await aesGcmDecrypt(ciphertext, iv, tag, rootKey);
    const legacy = parseLegacyPlain(legacyPlain);
    return ok(legacy ? unwrapPlaintext(legacy) : null, true);
  } catch {
    return null;
  }
}
