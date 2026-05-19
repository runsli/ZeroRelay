import type { Identity } from '../crypto/identity';
import type { RatchetState } from '../crypto/ratchet';
import { createIdentity } from '../crypto/identity';
import { dbGet, dbSet } from './db';

const KEYS = {
  identity: 'identity',
  contacts: 'contacts',
  config: 'config',
  ratchets: 'ratchets',
} as const;

export interface Contact {
  id: string;
  name: string;
  publicKey: string;
  verified: boolean;
  verifiedAt: number | null;
  addedAt: number;
}

export interface AppConfig {
  serverUrl: string | null;
}

/** Build-time default relay (optional Cloudflare Pages env). */
export function buildDefaultRelayUrl(): string | null {
  const raw = import.meta.env.VITE_DEFAULT_RELAY_URL?.trim();
  if (!raw) return null;
  const normalized = normalizeServerUrl(raw);
  return normalized || null;
}

/**
 * Runtime default: explicit env, or same origin when UI is served from the Worker
 * (one-click Deploy to Cloudflare bundles web + relay on one HTTPS host).
 */
export function defaultRelayUrlAtRuntime(): string | null {
  const preset = buildDefaultRelayUrl();
  if (preset) return preset;
  if (typeof window === 'undefined') return null;
  const { hostname, origin } = window.location;
  if (import.meta.env.PROD && hostname !== 'localhost' && hostname !== '127.0.0.1') {
    return origin;
  }
  return null;
}

export function normalizeServerUrl(raw: string): string {
  let s = String(raw || '').trim().replace(/\/+$/, '');
  if (!s) return '';
  if (!s.includes('://')) {
    const host = s.split('/')[0].split(':')[0]!;
    const local =
      host === 'localhost' ||
      host === '127.0.0.1' ||
      host === '10.0.2.2' ||
      host.startsWith('192.168.') ||
      host.startsWith('10.') ||
      host.endsWith('.local');
    s = `${local ? 'http' : 'https'}://${s}`;
  }
  return s.replace(/\/+$/, '');
}

/** Vite dev: optional proxy at /relay → localhost:8787 */
export function effectiveServerUrl(url: string): string {
  const normalized = normalizeServerUrl(url);
  if (import.meta.env.DEV && normalized === 'http://127.0.0.1:8787') {
    return `${window.location.origin}/relay`;
  }
  if (import.meta.env.DEV && normalized === 'http://localhost:8787') {
    return `${window.location.origin}/relay`;
  }
  return normalized;
}

export async function loadConfig(): Promise<AppConfig> {
  return (await dbGet<AppConfig>(KEYS.config)) ?? { serverUrl: null };
}

export async function saveServerUrl(raw: string): Promise<string> {
  const serverUrl = normalizeServerUrl(raw);
  const cfg = await loadConfig();
  cfg.serverUrl = serverUrl || null;
  await dbSet(KEYS.config, cfg);
  return serverUrl;
}

export async function loadOrCreateIdentity(): Promise<Identity> {
  let id = await dbGet<Identity>(KEYS.identity);
  if (!id) {
    id = createIdentity();
    await dbSet(KEYS.identity, id);
  }
  return id;
}

export async function loadContacts(): Promise<Contact[]> {
  return (await dbGet<Contact[]>(KEYS.contacts)) ?? [];
}

export async function saveContacts(contacts: Contact[]): Promise<void> {
  await dbSet(KEYS.contacts, contacts);
}

export async function loadAllRatchets(): Promise<Record<string, RatchetState>> {
  return (await dbGet<Record<string, RatchetState>>(KEYS.ratchets)) ?? {};
}

export async function saveRatchetState(roomId: string, state: RatchetState): Promise<void> {
  const all = await loadAllRatchets();
  all[roomId] = state;
  await dbSet(KEYS.ratchets, all);
}

export function createRatchetStore() {
  return {
    load: async (roomId: string) => (await loadAllRatchets())[roomId] ?? null,
    save: saveRatchetState,
  };
}
