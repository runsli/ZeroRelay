/**
 * ZeroRelay Server - Cloudflare Workers Backend
 */

import { Hono } from 'hono';
import { cors } from 'hono/cors';
import {
  createMessageStore,
  type Message,
  type MessageStore,
} from './message-store';
import {
  LIMITS,
  WS_AUTH_TIMEOUT_MS,
  assertMessageAcceptable,
  checkRateLimit,
  getClientIp,
  validateFieldSizes,
  verifyRouteHash,
  parseRouteHashB64,
  base64ToBytes,
  routeIdFromTokenHash,
  senderIdFromPublicKeyAsync,
} from './relay-security';
import { logRelayError } from './relay-log';
import type { StorePolicyEnv } from './store-policy';
import { decodeTunnelFrame } from './relay-tunnel';
import {
  legacyHttpEnabled,
  LEGACY_HTTP_DISABLED_ERROR,
  LEGACY_HTTP_HEADERS,
} from './relay-policy';

function applyLegacyHttpHeaders(c: { header: (name: string, value: string) => void }) {
  for (const [name, value] of Object.entries(LEGACY_HTTP_HEADERS)) {
    c.header(name, value);
  }
}

interface Env extends StorePolicyEnv {
  MESSAGE_KV?: KVNamespace;
  ENVIRONMENT?: string;
  RELAY_LOG?: string;
  ENABLE_LEGACY_HTTP?: string;
}

const WS_HISTORY_LIMIT = 80;

interface WsSession {
  routeId: string;
  senderId: string;
  authenticated: boolean;
  authDeadline: number;
}

const wsSessions = new WeakMap<WebSocket, WsSession>();

function sliceRecentMessages(
  messages: Message[],
  limit: number,
  since = 0,
): Message[] {
  const filtered = messages.filter((m) => m.timestamp > since);
  if (filtered.length <= limit) return filtered;
  return filtered.slice(-limit);
}

function buildHistoryPayload(messages: Message[], limit = WS_HISTORY_LIMIT) {
  const totalCount = messages.length;
  const recent = sliceRecentMessages(messages, limit, 0);
  return {
    type: 'history' as const,
    messages: recent,
    limit,
    returnedCount: recent.length,
    totalCount,
    truncated: totalCount > recent.length,
    oldestTimestamp: recent.length > 0 ? recent[0].timestamp : null,
  };
}

const app = new Hono<{ Bindings: Env }>();
const websocketRooms = new Map<string, Set<WebSocket>>();

function getRoomClients(roomId: string): Set<WebSocket> {
  if (!websocketRooms.has(roomId)) {
    websocketRooms.set(roomId, new Set());
  }
  return websocketRooms.get(roomId)!;
}

function broadcastRoom(roomId: string, payload: object) {
  const clients = websocketRooms.get(roomId);
  if (!clients) return;
  const data = JSON.stringify(payload);
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(data);
    }
  }
}

function sendSocketMessage(ws: WebSocket, payload: object) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

async function persistMessage(
  store: MessageStore,
  roomId: string,
  message: Message,
) {
  await store.addMessage(roomId, message);
  broadcastRoom(roomId, { type: 'message', message });
}

function routeHashFromRequest(
  c: {
    req: {
      header: (name: string) => string | undefined;
      query: (name: string) => string | undefined;
    };
  },
  body?: { routeHash?: string },
): Uint8Array | null {
  const b64 =
    c.req.header('X-Route-Hash') ||
    body?.routeHash ||
    c.req.query('routeHash') ||
    undefined;
  if (!b64) return null;
  return parseRouteHashB64(b64);
}

interface PollRequestBody {
  since?: number;
  timeout?: number;
  routeHash?: string;
}

async function handlePollMessages(
  env: Env,
  ip: string,
  routeId: string,
  since: number,
  waitTimeout: number,
) {
  const rateErr = await checkRateLimit(env.MESSAGE_KV, ip, routeId, 'messages');
  if (rateErr) return { status: 429 as const, body: { error: rateErr } };

  const store = createMessageStore(env);
  void waitTimeout;
  let payload = await store.getMessagesSince(routeId, since);

  if (since === 0 && payload.length > WS_HISTORY_LIMIT) {
    payload = payload.slice(-WS_HISTORY_LIMIT);
  }

  if (payload.length > 0) {
    const totalInRoom =
      since === 0 ? await store.getMessageCount(routeId) : payload.length;
    return {
      status: 200 as const,
      body: {
        success: true,
        messages: payload,
        count: payload.length,
        totalCount: totalInRoom,
        truncated: since === 0 && totalInRoom > payload.length,
        timestamp: Date.now(),
      },
    };
  }

  return {
    status: 200 as const,
    body: {
      success: true,
      messages: [],
      count: 0,
      timestamp: Date.now(),
    },
  };
}

async function completeWsAuth(
  env: Env,
  server: WebSocket,
  session: WsSession,
  routeHash: Uint8Array,
  store: MessageStore,
): Promise<string | null> {
  const routeId = routeIdFromTokenHash(routeHash);
  const msgCount = await store.getMessageCount(routeId);
  const auth = await verifyRouteHash(env.MESSAGE_KV, routeHash, {
    messageCount: msgCount,
  });
  if ('error' in auth) return auth.error;
  session.routeId = auth.routeId;

  session.authenticated = true;
  getRoomClients(session.routeId).add(server);

  const allHistory = await store.getMessagesSince(session.routeId, 0);
  sendSocketMessage(server, buildHistoryPayload(allHistory));
  sendSocketMessage(server, { type: 'joined' });
  sendSocketMessage(server, { type: 'auth_ok' });
  return null;
}

app.use(
  '*',
  cors({
    origin: '*',
    allowMethods: ['GET', 'POST', 'OPTIONS'],
    allowHeaders: ['Content-Type', 'Authorization', 'X-Route-Hash'],
  }),
);

function healthJson() {
  return {
    status: 'ok',
    service: 'ZeroRelay Server',
    version: '1.3.0',
    timestamp: Date.now(),
  };
}

app.get('/', (c) => c.json(healthJson()));
app.get('/health', (c) => c.json(healthJson()));

app.post('/send', async (c) => {
  if (!legacyHttpEnabled(c.env)) {
    return c.json({ error: LEGACY_HTTP_DISABLED_ERROR }, 410);
  }
  try {
    const raw = await c.req.text();
    if (raw.length > LIMITS.maxJsonBytes) {
      return c.json({ error: 'request body too large' }, 413);
    }
    const body = JSON.parse(raw) as Record<string, unknown>;

    const fieldErr = validateFieldSizes(body);
    if (fieldErr) return c.json({ error: fieldErr }, 400);

    if (!body.ciphertext || !body.iv || !body.tag) {
      return c.json(
        {
          error: 'Missing required fields',
          required: ['ciphertext', 'iv', 'tag', 'senderPk', 'signPk', 'sig'],
        },
        400,
      );
    }

    const routeHash = routeHashFromRequest(c);
    if (!routeHash) return c.json({ error: 'missing route hash' }, 403);
    const store = createMessageStore(c.env);
    const routeId = routeIdFromTokenHash(routeHash);
    const msgCount = await store.getMessageCount(routeId);
    const auth = await verifyRouteHash(c.env.MESSAGE_KV, routeHash, { messageCount: msgCount });
    if ('error' in auth) return c.json({ error: auth.error }, 403);

    const ip = getClientIp(c.req.raw);
    const rateErr = await checkRateLimit(c.env.MESSAGE_KV, ip, auth.routeId, 'send');
    if (rateErr) return c.json({ error: rateErr }, 429);

    const timestamp = typeof body.timestamp === 'number' ? body.timestamp : Date.now();
    const senderPk = String(body.senderPk || '');
    let senderId = String(body.senderId || '');
    const senderPkBytes = senderPk ? base64ToBytes(senderPk) : null;
    if (senderPkBytes?.length === 32) {
      senderId = await senderIdFromPublicKeyAsync(senderPkBytes);
    }
    if (!senderId) senderId = 'anonymous';

    const signed = {
      routeId: auth.routeId,
      senderId,
      senderPk,
      signPk: String(body.signPk || ''),
      sig: String(body.sig || ''),
      ciphertext: String(body.ciphertext),
      iv: String(body.iv),
      tag: String(body.tag),
      timestamp,
    };

    const acceptErr = await assertMessageAcceptable(c.env.MESSAGE_KV, signed);
    if (acceptErr) return c.json({ error: acceptErr }, 403);

    const message: Message = {
      id: generateId(),
      roomId: '',
      ciphertext: signed.ciphertext,
      iv: signed.iv,
      tag: signed.tag,
      timestamp,
      senderId: signed.senderId,
      senderPk: signed.senderPk,
      signPk: signed.signPk,
      sig: signed.sig,
    };

    await persistMessage(store, auth.routeId, message);

    applyLegacyHttpHeaders(c);
    return c.json({
      success: true,
      messageId: message.id,
      timestamp: message.timestamp,
    });
  } catch (error) {
    logRelayError(c.env, 'Send message error:', error);
    return c.json({ error: 'Internal server error' }, 500);
  }
});

app.get('/ws', async (c) => {
  try {
    const requestUrl = new URL(c.req.url);
    const ip = getClientIp(c.req.raw);
    const rateErr = await checkRateLimit(c.env.MESSAGE_KV, ip, '_ws', 'ws');
    if (rateErr) return c.json({ error: rateErr }, 429);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    server.accept();

    const store = createMessageStore(c.env);
    const session: WsSession = {
      routeId: '',
      senderId: 'anonymous',
      authenticated: false,
      authDeadline: Date.now() + WS_AUTH_TIMEOUT_MS,
    };
    wsSessions.set(server, session);

    const authTimeout = setTimeout(() => {
      const current = wsSessions.get(server);
      if (current && !current.authenticated && server.readyState === WebSocket.OPEN) {
        sendSocketMessage(server, { type: 'auth_error', error: 'authentication timeout' });
        server.close(4001, 'auth timeout');
      }
    }, WS_AUTH_TIMEOUT_MS);

    server.addEventListener('message', async (event) => {
      try {
        const current = wsSessions.get(server);
        if (!current) return;

        const data =
          typeof event.data === 'string'
            ? JSON.parse(event.data)
            : JSON.parse(new TextDecoder().decode(event.data as ArrayBuffer));

        if (!current.authenticated) {
          if (data?.type === 'auth' && data.routeHash) {
            current.senderId =
              typeof data.senderId === 'string' && data.senderId.trim()
                ? data.senderId.trim()
                : 'anonymous';
            const routeHash = parseRouteHashB64(String(data.routeHash));
            if (!routeHash) {
              sendSocketMessage(server, { type: 'auth_error', error: 'invalid routeHash' });
              server.close(4003, 'invalid routeHash');
              return;
            }
            const err = await completeWsAuth(c.env, server, current, routeHash, store);
            if (err) {
              sendSocketMessage(server, { type: 'auth_error', error: err });
              server.close(4003, err);
              return;
            }
            clearTimeout(authTimeout);
            return;
          }
          sendSocketMessage(server, {
            type: 'auth_error',
            error: 'send { type: "auth", routeHash, senderId } first',
          });
          server.close(4003, 'auth required');
          return;
        }

        if (data?.type === 'send') {
          const signed = {
            routeId: current.routeId,
            senderId: data.senderId || current.senderId,
            senderPk: data.senderPk || '',
            signPk: data.signPk || '',
            sig: data.sig || '',
            ciphertext: data.ciphertext,
            iv: data.iv,
            tag: data.tag,
            timestamp: data.timestamp || Date.now(),
          };
          const acceptErr = await assertMessageAcceptable(c.env.MESSAGE_KV, signed);
          if (acceptErr) {
            sendSocketMessage(server, { type: 'error', error: acceptErr });
            return;
          }

          const message: Message = {
            id: generateId(),
            roomId: '',
            ciphertext: signed.ciphertext,
            iv: signed.iv,
            tag: signed.tag,
            timestamp: signed.timestamp,
            senderId: signed.senderId,
            senderPk: signed.senderPk,
            signPk: signed.signPk,
            sig: signed.sig,
          };
          await persistMessage(store, current.routeId, message);
        }
      } catch (error) {
        logRelayError(c.env, 'WebSocket message parse error:', error);
      }
    });

    server.addEventListener('close', () => {
      clearTimeout(authTimeout);
      const current = wsSessions.get(server);
      if (current) {
        const roomClientsSet = websocketRooms.get(current.routeId);
        roomClientsSet?.delete(server);
        if (roomClientsSet?.size === 0) {
          websocketRooms.delete(current.routeId);
        }
      }
      wsSessions.delete(server);
    });

    sendSocketMessage(server, {
      type: 'auth_required',
      timeoutMs: WS_AUTH_TIMEOUT_MS,
    });

    return new Response(null, { status: 101, webSocket: client });
  } catch (error) {
    logRelayError(c.env, 'WebSocket upgrade error:', error);
    return c.json({ error: 'WebSocket upgrade failed' }, 500);
  }
});

/** Prefer POST /t (tunnel) or POST /messages with X-Route-Hash. */
app.post('/messages', async (c) => {
  if (!legacyHttpEnabled(c.env)) {
    return c.json({ error: LEGACY_HTTP_DISABLED_ERROR }, 410);
  }
  try {
    const body = (await c.req.json()) as PollRequestBody;
    const routeHash = routeHashFromRequest(c, body);
    if (!routeHash) return c.json({ error: 'missing route hash' }, 403);
    const store = createMessageStore(c.env);
    const routeId = routeIdFromTokenHash(routeHash);
    const auth = await verifyRouteHash(c.env.MESSAGE_KV, routeHash, {
      messageCount: await store.getMessageCount(routeId),
    });
    if ('error' in auth) return c.json({ error: auth.error }, 403);

    const ip = getClientIp(c.req.raw);
    const result = await handlePollMessages(
      c.env,
      ip,
      auth.routeId,
      typeof body.since === 'number' ? body.since : 0,
      typeof body.timeout === 'number' ? body.timeout : 8000,
    );
    applyLegacyHttpHeaders(c);
    return c.json(result.body, result.status);
  } catch (error) {
    logRelayError(c.env, 'Poll messages error:', error);
    return c.json({ error: 'Internal server error' }, 500);
  }
});

/** 全隧道：application/octet-stream，无 JSON 字段名泄露 */
app.post('/t', async (c) => {
  try {
    const body = await c.req.arrayBuffer();
    if (body.byteLength > LIMITS.maxJsonBytes) {
      return c.json({ error: 'tunnel frame too large' }, 413);
    }
    const decoded = await decodeTunnelFrame(body);
    if ('error' in decoded) return c.json({ error: decoded.error }, 400);

    const ip = getClientIp(c.req.raw);
    const store = createMessageStore(c.env);
    const msgCount = await store.getMessageCount(decoded.routeId);
    const auth = await verifyRouteHash(c.env.MESSAGE_KV, decoded.routeHash, {
      messageCount: msgCount,
    });
    if ('error' in auth) return c.json({ error: auth.error }, 403);

    if (decoded.inner.op === 'poll') {
      const rateErr = await checkRateLimit(c.env.MESSAGE_KV, ip, auth.routeId, 'messages');
      if (rateErr) return c.json({ error: rateErr }, 429);
      const result = await handlePollMessages(
        c.env,
        ip,
        auth.routeId,
        decoded.inner.since,
        decoded.inner.timeout,
      );
      return c.json(result.body, result.status);
    }

    const inner = decoded.inner;
    const rateErr = await checkRateLimit(c.env.MESSAGE_KV, ip, auth.routeId, 'send');
    if (rateErr) return c.json({ error: rateErr }, 429);

    const senderPkBytes = base64ToBytes(inner.senderPk);
    const senderId =
      senderPkBytes?.length === 32
        ? await senderIdFromPublicKeyAsync(senderPkBytes)
        : 'anonymous';

    const signed = {
      routeId: auth.routeId,
      senderId,
      senderPk: inner.senderPk,
      signPk: inner.signPk,
      sig: inner.sig,
      ciphertext: inner.ciphertext,
      iv: inner.iv,
      tag: inner.tag,
      timestamp: inner.timestamp,
    };
    const acceptErr = await assertMessageAcceptable(c.env.MESSAGE_KV, signed);
    if (acceptErr) return c.json({ error: acceptErr }, 403);

    const message: Message = {
      id: generateId(),
      roomId: '',
      ciphertext: signed.ciphertext,
      iv: signed.iv,
      tag: signed.tag,
      timestamp: signed.timestamp,
      senderId: signed.senderId,
      senderPk: signed.senderPk,
      signPk: signed.signPk,
      sig: signed.sig,
    };
    await persistMessage(store, auth.routeId, message);
    return c.json({
      success: true,
      messageId: message.id,
      timestamp: message.timestamp,
    });
  } catch (error) {
    logRelayError(c.env, 'Tunnel error:', error);
    return c.json({ error: 'Internal server error' }, 500);
  }
});

export default app;
