import { encodeTunnelFrame, type TunnelInner } from '../crypto/relay-tunnel';
import type { RelayMessage } from '../crypto/relay-crypto';

export interface PollResult {
  success?: boolean;
  messages?: RelayMessage[];
  error?: string;
}

export async function tunnelRequest(
  serverUrl: string,
  tokenB64: string,
  inner: TunnelInner,
): Promise<unknown> {
  const { frame } = await encodeTunnelFrame(tokenB64, inner);
  const res = await fetch(new URL('/t', serverUrl.endsWith('/') ? serverUrl : `${serverUrl}/`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: frame,
  });
  const text = await res.text();
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return { error: text || 'Invalid JSON response' };
  }
}

export async function pollMessages(
  serverUrl: string,
  tokenB64: string,
  since: number,
  timeout: number,
): Promise<RelayMessage[]> {
  const result = (await tunnelRequest(serverUrl, tokenB64, {
    op: 'poll',
    since,
    timeout,
  })) as PollResult;
  if (result.messages?.length) return result.messages;
  return [];
}

export async function sendMessage(
  serverUrl: string,
  tokenB64: string,
  inner: Extract<TunnelInner, { op: 'send' }>,
): Promise<boolean> {
  const result = (await tunnelRequest(serverUrl, tokenB64, inner)) as { success?: boolean };
  return result.success !== false;
}

export function webSocketUrl(serverUrl: string): string {
  const base = new URL(serverUrl.endsWith('/') ? serverUrl : `${serverUrl}/`);
  const scheme = base.protocol === 'https:' ? 'wss' : 'ws';
  base.protocol = `${scheme}:`;
  base.pathname = '/ws';
  base.search = '';
  return base.toString();
}
