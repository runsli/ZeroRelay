/**
 * WebSocket 收消息（与 Android ChatRepository / ServerUrl.webSocketUrl 对齐）
 */
const WebSocket = require('ws');
const https = require('https');
const identityStore = require('./cli-identity-store');
const relayCrypto = require('./cli-relay-crypto');

function webSocketUrl(serverUrl) {
  const base = new URL(serverUrl.endsWith('/') ? serverUrl : `${serverUrl}/`);
  const scheme = base.protocol === 'https:' ? 'wss' : 'ws';
  base.protocol = `${scheme}:`;
  base.pathname = '/ws';
  base.search = '';
  return base.toString();
}

function tlsAgentForUrl(dataDir, serverUrl) {
  const reqUrl = new URL(serverUrl);
  if (reqUrl.protocol !== 'https:') return undefined;
  const pins = identityStore.getTlsPinsForHost(dataDir, reqUrl.hostname);
  return new https.Agent({
    checkServerIdentity: (host, cert) => {
      const pin = relayCrypto.certPinFromPeerCert(cert);
      if (pins.length > 0) {
        if (!pin || !pins.includes(pin)) {
          throw new Error(`TLS 证书 pin 不匹配 (${host})`);
        }
      } else if (pin) {
        identityStore.rememberTlsPin(dataDir, host, pin);
      }
      return undefined;
    },
  });
}

class RelayWebSocket {
  /**
   * @param {object} opts
   * @param {string} opts.serverUrl
   * @param {string} opts.dataDir
   * @param {string} opts.roomId
   * @param {string} opts.senderId
   * @param {string} opts.roomAccessToken
   * @param {(msg: object) => void} opts.onMessage
   * @param {(status: 'connecting'|'connected'|'disconnected'|'error', detail?: string) => void} [opts.onStatus]
   */
  constructor(opts) {
    this.serverUrl = opts.serverUrl;
    this.dataDir = opts.dataDir;
    this.roomId = opts.roomId;
    this.senderId = opts.senderId;
    this.roomAccessToken = opts.roomAccessToken;
    this.onMessage = opts.onMessage;
    this.onStatus = opts.onStatus || (() => {});
    this.ws = null;
    this.authenticated = false;
    this.closed = false;
    this.reconnectTimer = null;
    this.reconnectAttempt = 0;
  }

  connect() {
    if (this.closed) return;
    this.authenticated = false;
    this.onStatus('connecting');
    const url = webSocketUrl(this.serverUrl);
    const agent = tlsAgentForUrl(this.dataDir, this.serverUrl);
    const wsOpts = agent ? { agent } : {};
    const ws = new WebSocket(url, wsOpts);
    this.ws = ws;

    const sendAuth = () => {
      if (ws.readyState !== WebSocket.OPEN) return;
      const routeHash = relayCrypto.routeHashB64FromToken(this.roomAccessToken);
      if (!routeHash) {
        this.onStatus('error', 'invalid room access token');
        ws.close();
        return;
      }
      ws.send(
        JSON.stringify({
          type: 'auth',
          routeHash,
          senderId: this.senderId,
        }),
      );
    };

    ws.on('open', sendAuth);

    ws.on('message', (raw) => {
      let data;
      try {
        data = JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8'));
      } catch {
        return;
      }
      switch (data.type) {
        case 'auth_ok':
        case 'joined':
          this.authenticated = true;
          this.reconnectAttempt = 0;
          this.onStatus('connected');
          break;
        case 'auth_required':
          sendAuth();
          break;
        case 'auth_error':
          this.onStatus('error', data.error || 'auth failed');
          ws.close();
          break;
        case 'message':
          if (data.message) this.onMessage(data.message);
          break;
        case 'history':
          if (Array.isArray(data.messages)) {
            for (const msg of data.messages) this.onMessage(msg);
          }
          break;
        default:
          break;
      }
    });

    ws.on('close', () => {
      if (this.closed || this.ws !== ws) return;
      this.authenticated = false;
      this.onStatus('disconnected');
      this.scheduleReconnect();
    });

    ws.on('error', (err) => {
      if (this.closed || this.ws !== ws) return;
      this.onStatus('error', err.message || String(err));
    });
  }

  scheduleReconnect() {
    if (this.closed || this.reconnectTimer) return;
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 15000);
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.closed) this.connect();
    }, delay);
  }

  close() {
    this.closed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const ws = this.ws;
    this.ws = null;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.close(1000);
    } else if (ws) {
      ws.terminate();
    }
    this.authenticated = false;
  }

  isConnected() {
    return this.authenticated && this.ws && this.ws.readyState === WebSocket.OPEN;
  }
}

module.exports = { RelayWebSocket, webSocketUrl };
