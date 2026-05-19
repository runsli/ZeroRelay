/**
 * CLI 本地配置（服务器、TLS pin、最近联系人/房间 — 对齐 Android UserPreferences）
 */
const fs = require('fs');
const path = require('path');

const CONFIG_FILE = 'config.json';
const MAX_RECENT = 10;

function configPath(dataDir) {
  return path.join(dataDir, CONFIG_FILE);
}

function defaultConfig() {
  return {
    serverUrl: null,
    pendingTlsPin: null,
    recentContactIds: [],
    recentRoomIds: [],
  };
}

function loadConfig(dataDir) {
  const file = configPath(dataDir);
  if (!fs.existsSync(file)) return defaultConfig();
  try {
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    return {
      serverUrl: json.serverUrl || null,
      pendingTlsPin: json.pendingTlsPin || null,
      recentContactIds: Array.isArray(json.recentContactIds) ? json.recentContactIds : [],
      recentRoomIds: Array.isArray(json.recentRoomIds) ? json.recentRoomIds : [],
    };
  } catch {
    return defaultConfig();
  }
}

function saveConfig(dataDir, config) {
  fs.mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(configPath(dataDir), JSON.stringify(config, null, 2), { mode: 0o600 });
  try {
    fs.chmodSync(configPath(dataDir), 0o600);
  } catch {
    /* ignore */
  }
}

function setServerUrl(dataDir, url) {
  const cfg = loadConfig(dataDir);
  cfg.serverUrl = url;
  saveConfig(dataDir, cfg);
}

function rememberServerUrl(dataDir, url) {
  const normalized = normalizeServerUrl(url);
  if (!normalized) return;
  setServerUrl(dataDir, normalized);
}

function setPendingTlsPin(dataDir, host, pin) {
  const cfg = loadConfig(dataDir);
  cfg.pendingTlsPin = { host, pin };
  saveConfig(dataDir, cfg);
}

function clearPendingTlsPin(dataDir) {
  const cfg = loadConfig(dataDir);
  cfg.pendingTlsPin = null;
  saveConfig(dataDir, cfg);
}

function addRecentContact(dataDir, contactId) {
  if (!contactId) return;
  const cfg = loadConfig(dataDir);
  const next = [contactId, ...cfg.recentContactIds.filter((id) => id !== contactId)].slice(
    0,
    MAX_RECENT,
  );
  cfg.recentContactIds = next;
  saveConfig(dataDir, cfg);
}

function addRecentRoom(dataDir, roomId) {
  if (!roomId) return;
  const cfg = loadConfig(dataDir);
  const next = [roomId, ...cfg.recentRoomIds.filter((id) => id !== roomId)].slice(0, MAX_RECENT);
  cfg.recentRoomIds = next;
  saveConfig(dataDir, cfg);
}

/** 与 Android ServerUrl.normalize 对齐 */
function normalizeServerUrl(raw) {
  let s = String(raw || '').trim().replace(/\/+$/, '');
  if (!s) return '';
  if (!s.includes('://')) {
    const host = s.split('/')[0].split(':')[0];
    const local =
      host === 'localhost' ||
      host === '10.0.2.2' ||
      host.startsWith('127.') ||
      host.startsWith('192.168.') ||
      host.startsWith('10.') ||
      host.endsWith('.local');
    s = `${local ? 'http' : 'https'}://${s}`;
  }
  return s.replace(/\/+$/, '');
}

module.exports = {
  loadConfig,
  saveConfig,
  setServerUrl,
  rememberServerUrl,
  setPendingTlsPin,
  clearPendingTlsPin,
  addRecentContact,
  addRecentRoom,
  normalizeServerUrl,
  MAX_RECENT,
};
