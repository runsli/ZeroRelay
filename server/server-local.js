/**
 * ZeroRelay Server - 本地开发版本
 * 使用 Node.js 原生 HTTP 服务器 (兼容 Node 16+)
 */

const http = require('http');
const url = require('url');
const WebSocket = require('ws');
const transportLog = require('./transport-log');
const relaySecurity = require('./relay-security.cjs');
const relayTunnel = require('../cli-relay-tunnel');
const { resolveStorePolicy } = require('./store-policy.js');
const relayPolicy = require('./relay-policy.cjs');

const storePolicy = resolveStorePolicy(process.env);
const relayLog = process.env.RELAY_LOG === '1';

/** WebSocket 进房时推送的最近消息条数（可用 WS_HISTORY_LIMIT 覆盖） */
const WS_HISTORY_LIMIT = Math.max(
  1,
  parseInt(process.env.WS_HISTORY_LIMIT || '80', 10) || 80
);

// 内存存储
class MemoryStore {
  constructor() {
    this.messages = new Map();
    this.waiters = new Map(); // 记录等待新消息的客户端
  }

  addMessage(roomId, message) {
    if (!this.messages.has(roomId)) {
      this.messages.set(roomId, []);
    }
    this.messages.get(roomId).push(message);
    
    const messages = this.messages.get(roomId);
    const max = storePolicy.maxMessagesPerRoom;
    if (messages.length > max) {
      this.messages.set(roomId, messages.slice(-max));
    }

    // 通知所有等待此房间的客户端
    this.notifyWaiters(roomId);
  }

  getMessagesSince(roomId, since) {
    const messages = this.messages.get(roomId) || [];
    return messages.filter(m => m.timestamp > since);
  }

  /** 最近 N 条（since 之后），按时间升序 */
  getRecentMessages(roomId, limit, since = 0) {
    const filtered = this.getMessagesSince(roomId, since);
    if (filtered.length <= limit) return filtered;
    return filtered.slice(-limit);
  }

  getMessageCount(roomId) {
    return (this.messages.get(roomId) || []).length;
  }

  buildHistoryPayload(roomId, limit = WS_HISTORY_LIMIT) {
    const totalCount = this.getMessageCount(roomId);
    const messages = this.getRecentMessages(roomId, limit, 0);
    return {
      type: 'history',
      messages,
      limit,
      returnedCount: messages.length,
      totalCount,
      truncated: totalCount > messages.length,
      oldestTimestamp: messages.length > 0 ? messages[0].timestamp : null,
    };
  }

  // 注册一个等待者
  addWaiter(roomId, callback) {
    if (!this.waiters.has(roomId)) {
      this.waiters.set(roomId, []);
    }
    this.waiters.get(roomId).push(callback);
  }

  // 通知所有等待者
  notifyWaiters(roomId) {
    const callbacks = this.waiters.get(roomId) || [];
    callbacks.forEach(cb => {
      try {
        cb();
      } catch (e) {
        if (relayLog) console.error('Error notifying waiter:', e);
      }
    });
    this.waiters.set(roomId, []);
  }

  getRoomInfo(roomId) {
    const messages = this.messages.get(roomId) || [];
    return {
      messageCount: messages.length,
    };
  }
}

const memoryStore = new MemoryStore();
const roomClients = new Map();

function wsClientCount(roomId) {
  return roomClients.get(roomId)?.size ?? 0;
}

function broadcastToRoom(roomId, payload) {
  const clients = roomClients.get(roomId);
  if (!clients) return 0;
  const data = JSON.stringify(payload);
  let sent = 0;
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(data);
      sent++;
    }
  }
  if (sent > 0 && payload.type) {
    transportLog.broadcast({ roomId, type: payload.type, recipients: sent });
  }
  return sent;
}

function storeAndBroadcastMessage(roomId, message, channel) {
  memoryStore.addMessage(roomId, message);
  const total = memoryStore.getMessageCount(roomId);
  transportLog.messageStored({ channel, roomId, message, totalInRoom: total });
  broadcastToRoom(roomId, { type: 'message', message });
}

function sendWsJson(ws, payload) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function createRoomClientSet(roomId) {
  if (!roomClients.has(roomId)) {
    roomClients.set(roomId, new Set());
  }
  return roomClients.get(roomId);
}

// 生成唯一 ID
function generateId() {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

// CORS 头
const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Route-Hash',
};

function getClientIp(req) {
  return req.headers['x-forwarded-for']?.split(',')[0]?.trim() || req.socket.remoteAddress || 'unknown';
}

function routeHashFromReq(req, query, body) {
  const b64 = req.headers['x-route-hash'] || body?.routeHash || query?.routeHash || null;
  if (!b64) return null;
  return relaySecurity.parseRouteHashB64(b64);
}

// JSON 响应辅助函数
function sendJson(res, data, status = 200, extraHeaders = {}) {
  res.writeHead(status, {
    'Content-Type': 'application/json',
    ...corsHeaders,
    ...extraHeaders,
  });
  res.end(JSON.stringify(data));
}

function sendJsonLegacy(res, data, status = 200) {
  sendJson(res, data, status, relayPolicy.LEGACY_HTTP_HEADERS);
}

// 解析请求体
function parseBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function parseRawBody(req) {
  return new Promise((resolve, reject) => {
    const parts = [];
    req.on('data', (chunk) => parts.push(chunk));
    req.on('end', () => resolve(Buffer.concat(parts)));
    req.on('error', reject);
  });
}

function authRouteHash(routeHashBuf, messageCount = 0) {
  const auth = relaySecurity.verifyRouteHashMemory(routeHashBuf, { messageCount });
  if (auth.error) return { error: auth.error };
  return { routeId: auth.routeId };
}

// 路由处理
async function handleRequest(req, res) {
  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;
  const method = req.method;

  // OPTIONS 预检请求
  if (method === 'OPTIONS') {
    res.writeHead(204, corsHeaders);
    res.end();
    return;
  }

  try {
    // 健康检查
    if (pathname === '/' && method === 'GET') {
      sendJson(res, {
        status: 'ok',
        service: 'ZeroRelay Server (Local)',
        version: '1.0.0',
        timestamp: Date.now()
      });
      return;
    }

    // 发送消息
    if (pathname === '/send' && method === 'POST') {
      if (!relayPolicy.legacyHttpEnabled(process.env)) {
        sendJson(res, { error: relayPolicy.LEGACY_HTTP_DISABLED_ERROR }, 410);
        return;
      }
      const body = await parseBody(req);

      const fieldErr = relaySecurity.validateFieldSizes(body);
      if (fieldErr) {
        sendJson(res, { error: fieldErr }, 400);
        return;
      }

      if (!body.ciphertext || !body.iv || !body.tag) {
        sendJson(res, {
          error: 'Missing required fields',
          required: ['ciphertext', 'iv', 'tag', 'senderPk', 'signPk', 'sig'],
        }, 400);
        return;
      }

      const routeHash = routeHashFromReq(req, parsedUrl.query, body);
      if (!routeHash) {
        sendJson(res, { error: 'missing route hash' }, 403);
        return;
      }
      const auth = authRouteHash(routeHash, 0);
      if (auth.error) {
        if (relayLog) console.warn('[send] 403:', auth.error);
        sendJson(res, { error: auth.error }, 403);
        return;
      }

      const rateErr = relaySecurity.checkRateLimit(null, getClientIp(req), auth.routeId, 'send');
      if (rateErr) {
        sendJson(res, { error: rateErr }, 429);
        return;
      }

      const timestamp = body.timestamp || Date.now();
      const senderPk = body.senderPk || '';
      let senderId = body.senderId || '';
      if (senderPk) {
        try {
          senderId = relaySecurity.senderIdFromPublicKey(Buffer.from(senderPk, 'base64'));
        } catch {
          /* keep */
        }
      }
      if (!senderId) senderId = 'anonymous';

      const signed = {
        routeId: auth.routeId,
        senderId,
        senderPk,
        signPk: body.signPk || '',
        sig: body.sig || '',
        ciphertext: body.ciphertext,
        iv: body.iv,
        tag: body.tag,
        timestamp,
      };
      const acceptErr = relaySecurity.assertMessageAcceptable(signed);
      if (acceptErr) {
        if (relayLog) console.warn('[send] 403 message:', acceptErr);
        sendJson(res, { error: acceptErr }, 403);
        return;
      }

      const message = {
        id: generateId(),
        roomId: '',
        ciphertext: body.ciphertext,
        iv: body.iv,
        tag: body.tag,
        timestamp,
        senderId: signed.senderId,
        senderPk: signed.senderPk,
        signPk: signed.signPk,
        sig: signed.sig,
      };

      storeAndBroadcastMessage(auth.routeId, message, 'HTTP');

      sendJsonLegacy(res, {
        success: true,
        messageId: message.id,
        timestamp: message.timestamp
      });
      return;
    }

    async function handleMessagesPoll(routeId, since, timeout) {
      const rateErr = relaySecurity.checkRateLimit(null, getClientIp(req), routeId, 'messages');
      if (rateErr) {
        sendJson(res, { error: rateErr }, 429);
        return;
      }

      // 先检查是否有新消息
      let messages = memoryStore.getMessagesSince(routeId, since);
      const totalInRoom = memoryStore.getMessageCount(routeId);
      if (since === 0 && messages.length > WS_HISTORY_LIMIT) {
        messages = messages.slice(-WS_HISTORY_LIMIT);
      }

      if (messages.length > 0) {
        transportLog.httpPoll({ roomId: routeId, since, count: messages.length, waitedMs: 0 });
        sendJsonLegacy(res, {
          success: true,
          messages,
          count: messages.length,
          totalCount: totalInRoom,
          truncated: since === 0 && totalInRoom > messages.length,
          timestamp: Date.now()
        });
        return;
      }

      let responded = false;
      const startTime = Date.now();

      // 注册等待者回调
      const notifyFn = () => {
        if (responded) return;
        
        messages = memoryStore.getMessagesSince(routeId, since);
        if (messages.length > 0) {
          responded = true;
          transportLog.httpPoll({
            roomId: routeId,
            since,
            count: messages.length,
            waitedMs: Date.now() - startTime,
          });
          sendJsonLegacy(res, {
            success: true,
            messages,
            count: messages.length,
            timestamp: Date.now()
          });
        }
      };

      memoryStore.addWaiter(routeId, notifyFn);

      // 超时后返回空列表
      const timeoutHandle = setTimeout(() => {
        if (!responded) {
          responded = true;
          sendJsonLegacy(res, {
            success: true,
            messages: [],
            count: 0,
            timestamp: Date.now()
          });
        }
      }, timeout);

      // 连接关闭时清理
      res.on('close', () => {
        clearTimeout(timeoutHandle);
        responded = true;
      });

      return;
    }

    if (pathname === '/messages' && method === 'POST') {
      if (!relayPolicy.legacyHttpEnabled(process.env)) {
        sendJson(res, { error: relayPolicy.LEGACY_HTTP_DISABLED_ERROR }, 410);
        return;
      }
      const body = await parseBody(req);
      const routeHash = routeHashFromReq(req, parsedUrl.query, body);
      if (!routeHash) {
        sendJson(res, { error: 'missing route hash' }, 403);
        return;
      }
      const auth = authRouteHash(routeHash, 0);
      if (auth.error) {
        sendJson(res, { error: auth.error }, 403);
        return;
      }
      await handleMessagesPoll(
        auth.routeId,
        parseInt(body.since || '0', 10),
        parseInt(body.timeout || '8000', 10),
      );
      return;
    }

    if (pathname === '/messages' && method === 'GET') {
      sendJson(res, { error: 'GET /messages removed; use POST /t or POST /messages' }, 410);
      return;
    }

    if (pathname === '/t' && method === 'POST') {
      const raw = await parseRawBody(req);
      const decoded = relayTunnel.decodeTunnelFrame(raw);
      if (decoded.error) {
        sendJson(res, { error: decoded.error }, 400);
        return;
      }
      const auth = relaySecurity.verifyRouteHashMemory(
        decoded.routeHash,
        { messageCount: memoryStore.getMessageCount(decoded.routeId) },
      );
      if (auth.error) {
        sendJson(res, { error: auth.error }, 403);
        return;
      }
      if (decoded.inner.op === 'poll') {
        await handleMessagesPoll(
          auth.routeId,
          decoded.inner.since || 0,
          decoded.inner.timeout || 8000,
        );
        return;
      }
      const rateErr = relaySecurity.checkRateLimit(null, getClientIp(req), auth.routeId, 'send');
      if (rateErr) {
        sendJson(res, { error: rateErr }, 429);
        return;
      }
      const inner = decoded.inner;
      let senderId = 'anonymous';
      if (inner.senderPk) {
        try {
          senderId = relaySecurity.senderIdFromPublicKey(Buffer.from(inner.senderPk, 'base64'));
        } catch {
          /* ignore */
        }
      }
      const signed = {
        routeId: auth.routeId,
        senderId,
        senderPk: inner.senderPk,
        signPk: inner.signPk,
        sig: inner.sig,
        ciphertext: inner.ciphertext,
        iv: inner.iv,
        tag: inner.tag,
        timestamp: inner.timestamp || Date.now(),
      };
      const acceptErr = relaySecurity.assertMessageAcceptable(signed);
      if (acceptErr) {
        sendJson(res, { error: acceptErr }, 403);
        return;
      }
      const message = {
        id: generateId(),
        roomId: '',
        ...signed,
        timestamp: signed.timestamp,
      };
      storeAndBroadcastMessage(auth.routeId, message, 'tunnel');
      sendJson(res, { success: true, messageId: message.id, timestamp: message.timestamp });
      return;
    }

    // 404
    sendJson(res, { error: 'Not found' }, 404);

  } catch (error) {
    if (relayLog) console.error('Request error:', error);
    sendJson(res, { error: 'Internal server error' }, 500);
  }
}

// 创建服务器
const port = process.env.PORT || 8787;
const server = http.createServer(handleRequest);
const wss = new WebSocket.Server({ noServer: true });

function finishWsAuth(ws, meta, routeHashBuf) {
  const auth = relaySecurity.verifyRouteHashMemory(routeHashBuf, { messageCount: 0 });
  if (auth.error) {
    sendWsJson(ws, { type: 'auth_error', error: auth.error });
    ws.close(4003, auth.error);
    return false;
  }
  meta.routeId = auth.routeId;
  meta.authenticated = true;
  const clients = createRoomClientSet(meta.routeId);
  clients.add(ws);
  sendWsJson(ws, memoryStore.buildHistoryPayload(meta.routeId));
  sendWsJson(ws, { type: 'joined' });
  sendWsJson(ws, { type: 'auth_ok' });
  transportLog.wsConnect({
    roomId: meta.routeId,
    senderId: meta.senderId,
    online: clients.size,
  });
  return true;
}

server.on('upgrade', (req, socket, head) => {
  const requestUrl = new url.URL(req.url, `http://${req.headers.host}`);
  if (requestUrl.pathname !== '/ws') {
    socket.destroy();
    return;
  }

  const rateErr = relaySecurity.checkRateLimit(null, getClientIp(req), '_ws', 'ws');
  if (rateErr) {
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    const meta = {
      routeId: '',
      senderId: 'anonymous',
      authenticated: false,
    };
    ws._zrMeta = meta;

    const authTimeout = setTimeout(() => {
      if (!meta.authenticated && ws.readyState === WebSocket.OPEN) {
        sendWsJson(ws, { type: 'auth_error', error: 'authentication timeout' });
        ws.close(4001, 'auth timeout');
      }
    }, relaySecurity.WS_AUTH_TIMEOUT_MS);

    ws.on('message', (raw) => {
      try {
        const data = typeof raw === 'string' ? JSON.parse(raw) : JSON.parse(raw.toString());

        if (!meta.authenticated) {
          if (data?.type === 'auth' && data.routeHash) {
            const routeHash = relaySecurity.parseRouteHashB64(String(data.routeHash));
            if (!routeHash) {
              sendWsJson(ws, { type: 'auth_error', error: 'invalid routeHash' });
              ws.close(4003, 'invalid routeHash');
              return;
            }
            meta.senderId =
              typeof data.senderId === 'string' && data.senderId.trim()
                ? data.senderId.trim()
                : 'anonymous';
            if (finishWsAuth(ws, meta, routeHash)) {
              clearTimeout(authTimeout);
            }
            return;
          }
          sendWsJson(ws, {
            type: 'auth_error',
            error: 'send { type: "auth", routeHash, senderId } first',
          });
          ws.close(4003, 'auth required');
          return;
        }

        if (data?.type === 'send') {
          const signed = {
            routeId: meta.routeId,
            senderId: data.senderId || meta.senderId,
            senderPk: data.senderPk || '',
            signPk: data.signPk || '',
            sig: data.sig || '',
            ciphertext: data.ciphertext,
            iv: data.iv,
            tag: data.tag,
            timestamp: data.timestamp || Date.now(),
          };
          const acceptErr = relaySecurity.assertMessageAcceptable(signed);
          if (acceptErr) {
            sendWsJson(ws, { type: 'error', error: acceptErr });
            return;
          }
          const message = {
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
          storeAndBroadcastMessage(meta.routeId, message, 'WebSocket');
        }
      } catch (error) {
        if (relayLog) console.error('WebSocket message parse error:', error);
      }
    });

    ws.on('close', () => {
      clearTimeout(authTimeout);
      const roomClientsSet = roomClients.get(meta.routeId);
      roomClientsSet?.delete(ws);
      const remaining = roomClientsSet?.size ?? 0;
      transportLog.wsDisconnect({ roomId: meta.routeId, senderId: meta.senderId, online: remaining });
      if (remaining === 0) {
        roomClients.delete(meta.routeId);
      }
    });

    sendWsJson(ws, {
      type: 'auth_required',
      timeoutMs: relaySecurity.WS_AUTH_TIMEOUT_MS,
    });
  });
});

server.listen(port, () => {
  if (relayLog) {
    console.log('');
    console.log('╔════════════════════════════════════════════╗');
    console.log('║       ZeroRelay Server - Local Mode        ║');
    console.log('╠════════════════════════════════════════════╣');
    console.log(`║  Server running at http://localhost:${port}   ║`);
    console.log('╠════════════════════════════════════════════╣');
    console.log('║  Endpoints:                                ║');
    console.log('║  GET  /              - Health check        ║');
    console.log('║  POST /send          - Send message        ║');
    console.log('║  GET  /messages      - Poll messages (CLI) ║');
    console.log('║  GET  /ws            - WebSocket (App)     ║');
    console.log(`║  WS history limit: ${String(WS_HISTORY_LIMIT).padEnd(3)}                  ║`);
    console.log('╚════════════════════════════════════════════╝');
    transportLog.banner(port);
  } else {
    console.log(`ZeroRelay local server http://localhost:${port}`);
  }
});
