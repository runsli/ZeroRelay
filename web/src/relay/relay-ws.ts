import { b64encode } from '../crypto/bytes';
import { routeHashFromToken } from '../crypto/relay-crypto';
import type { RelayMessage } from '../crypto/relay-crypto';
import { webSocketUrl } from './relay-client';

export type WsStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

export class RelayWebSocket {
  private ws: WebSocket | null = null;
  private authenticated = false;
  private closed = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempt = 0;

  constructor(
    private readonly opts: {
      serverUrl: string;
      roomAccessToken: string;
      senderId: string;
      onMessage: (msg: RelayMessage) => void;
      onStatus?: (status: WsStatus, detail?: string) => void;
    },
  ) {}

  connect(): void {
    if (this.closed) return;
    this.authenticated = false;
    this.opts.onStatus?.('connecting');
    const ws = new WebSocket(webSocketUrl(this.opts.serverUrl));
    this.ws = ws;

    const sendAuth = async () => {
      if (ws.readyState !== WebSocket.OPEN) return;
      const routeHash = await routeHashFromToken(this.opts.roomAccessToken);
      if (!routeHash) {
        this.opts.onStatus?.('error', 'invalid room access token');
        ws.close();
        return;
      }
      ws.send(
        JSON.stringify({
          type: 'auth',
          routeHash: b64encode(routeHash),
          senderId: this.opts.senderId,
        }),
      );
    };

    ws.onopen = () => void sendAuth();

    ws.onmessage = (ev) => {
      let data: { type?: string; message?: RelayMessage; messages?: RelayMessage[]; error?: string };
      try {
        data = JSON.parse(String(ev.data)) as typeof data;
      } catch {
        return;
      }
      switch (data.type) {
        case 'auth_ok':
        case 'joined':
          this.authenticated = true;
          this.reconnectAttempt = 0;
          this.opts.onStatus?.('connected');
          break;
        case 'auth_required':
          void sendAuth();
          break;
        case 'auth_error':
          this.opts.onStatus?.('error', data.error || 'auth failed');
          ws.close();
          break;
        case 'message':
          if (data.message) this.opts.onMessage(data.message);
          break;
        case 'history':
          if (Array.isArray(data.messages)) {
            for (const msg of data.messages) this.opts.onMessage(msg);
          }
          break;
        default:
          break;
      }
    };

    ws.onclose = () => {
      if (this.closed || this.ws !== ws) return;
      this.authenticated = false;
      this.opts.onStatus?.('disconnected');
      this.scheduleReconnect();
    };

    ws.onerror = () => {
      if (this.closed || this.ws !== ws) return;
      this.opts.onStatus?.('error', 'WebSocket error');
    };
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer) return;
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 15000);
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.closed) this.connect();
    }, delay);
  }

  close(): void {
    this.closed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const ws = this.ws;
    this.ws = null;
    if (ws && ws.readyState === WebSocket.OPEN) ws.close(1000);
    else if (ws) ws.close();
    this.authenticated = false;
  }

  isConnected(): boolean {
    return this.authenticated && this.ws?.readyState === WebSocket.OPEN;
  }
}
