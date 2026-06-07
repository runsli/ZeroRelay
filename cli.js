#!/usr/bin/env node
/**
 * ZeroRelay CLI — X25519 身份密钥 + 联系人（阶段 A）
 */

const http = require('http');
const https = require('https');
const readline = require('readline');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

let x25519;
let qrcodeTerminal;
try {
  ({ x25519 } = require('@noble/curves/ed25519.js'));
  qrcodeTerminal = require('qrcode-terminal');
} catch {
  console.error('[-] 缺少依赖。新机器一键安装: ./scripts/cli-setup.sh');
  console.error('    或: npm install');
  process.exit(1);
}

const relayCrypto = require('./cli-relay-crypto');
const identityStore = require('./cli-identity-store');
const protocol = require('./cli-protocol');
const ratchetBackup = require('./cli-ratchet-backup');
const { RelayWebSocket } = require('./cli-ws');
const relayTunnel = require('./cli-relay-tunnel');
const { createInboundProcessor } = require('./cli-inbound');
const cliConfig = require('./cli-config');
const serverHealth = require('./cli-server-health');
const ERR = require('./cli-errors');
const { validatePassphrase, requirePassphrase } = require('./cli-passphrase');
const { terminalNotify } = require('./cli-notify');
const watchSessionStore = require('./cli-watch-session');
const { writeQrPng } = require('./cli-qr-export');
const DATA_DIR = path.join(os.homedir(), '.zero-relay');
const CONTACTS_FILE = path.join(DATA_DIR, 'contacts.json');
const GROUPS_FILE = path.join(DATA_DIR, 'groups.json');
const RATCHET_FILE = path.join(DATA_DIR, 'ratchets.json');
const HKDF_INFO = 'zero-relay-v1';
const GROUP_ROOM_SALT = 'zero-relay-group-v1';
const PROTOCOL_V2 = protocol.PROTOCOL_V2;
const GROUP_INVITE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const CRYPTO_LOG = process.env.CRYPTO_LOG === '1';
const CRYPTO_LOG_PLAINTEXT = process.env.CRYPTO_LOG_PLAINTEXT === '1';

function keyFingerprint(key) {
  return crypto.createHash('sha256').update(key).digest().slice(0, 4).toString('hex');
}

function cryptoLog(...parts) {
  if (!CRYPTO_LOG) return;
  console.log('[crypto]', ...parts);
}

function previewText(text) {
  if (!CRYPTO_LOG_PLAINTEXT) return;
  const s = String(text);
  cryptoLog('  明文:', s.length <= 40 ? `"${s}"` : `"${s.slice(0, 40)}…"`);
}

function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  try {
    fs.chmodSync(DATA_DIR, 0o700);
  } catch {
    /* ignore */
  }
}

function getServerUrl() {
  const fromArgv = process.argv.find((a) => a.startsWith('http'));
  if (fromArgv) return cliConfig.normalizeServerUrl(fromArgv);
  const cfg = cliConfig.loadConfig(DATA_DIR);
  if (cfg.serverUrl) return cliConfig.normalizeServerUrl(cfg.serverUrl);
  return 'http://localhost:8787';
}

function normalizeContact(raw) {
  return {
    id: raw.id,
    name: raw.name || raw.displayName || '联系人',
    publicKey: raw.publicKey || raw.pk,
    addedAt: raw.addedAt || raw.at || Date.now(),
    verified: !!(raw.verified ?? raw.vf),
    verifiedAt: raw.verifiedAt ?? raw.vfa ?? null,
  };
}

function serializeContact(c) {
  return {
    id: c.id,
    name: c.name,
    pk: c.publicKey,
    at: c.addedAt,
    vf: !!c.verified,
    ...(c.verifiedAt ? { vfa: c.verifiedAt } : {}),
  };
}

function normalizeGroup(raw) {
  const prevRaw = raw.previousKeys || raw.pk || {};
  const previousKeys = {};
  if (prevRaw && typeof prevRaw === 'object' && !Array.isArray(prevRaw)) {
    for (const [k, v] of Object.entries(prevRaw)) {
      const ver = parseInt(k, 10);
      if (!Number.isNaN(ver) && typeof v === 'string') previousKeys[ver] = v;
    }
  }
  const members = raw.members ?? raw.m ?? [];
  return {
    id: raw.id,
    name: raw.name || raw.displayName,
    roomId: raw.roomId || raw.room,
    key: raw.key,
    members: Array.isArray(members) ? members : [],
    createdAt: raw.createdAt ?? raw.at ?? Date.now(),
    keyVersion: raw.keyVersion ?? raw.kv ?? 1,
    inviteExpiresAt: raw.inviteExpiresAt ?? raw.exp ?? null,
    previousKeys,
  };
}

function serializeGroup(g) {
  const o = {
    id: g.id,
    name: g.name,
    room: g.roomId,
    key: g.key,
    at: g.createdAt,
    kv: g.keyVersion || 1,
  };
  if (g.inviteExpiresAt) o.exp = g.inviteExpiresAt;
  if (g.members?.length) o.m = g.members;
  if (g.previousKeys && Object.keys(g.previousKeys).length > 0) {
    o.pk = {};
    for (const [ver, b64] of Object.entries(g.previousKeys)) {
      o.pk[String(ver)] = b64;
    }
  }
  return o;
}

function touchServerUsage() {
  cliConfig.rememberServerUrl(DATA_DIR, getServerUrl());
}

function validateOutgoingMessage(text) {
  const trimmed = String(text || '').trim();
  if (!trimmed) return { ok: false, error: null };
  try {
    protocol.padPlaintext(trimmed);
  } catch {
    return { ok: false, error: ERR.MESSAGE_TOO_LONG };
  }
  return { ok: true, text: trimmed };
}

function isGroupExpired(group) {
  return group.inviteExpiresAt != null && Date.now() > group.inviteExpiresAt;
}

function loadOrCreateIdentity() {
  ensureDataDir();
  return identityStore.loadIdentity(DATA_DIR, () => {
    const privateKey = x25519.utils.randomSecretKey();
    const publicKey = x25519.getPublicKey(privateKey);
    return {
      publicKey: Buffer.from(publicKey).toString('base64'),
      privateKey: Buffer.from(privateKey).toString('base64'),
    };
  });
}

function secureWriteJson(filePath, data) {
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2), { mode: 0o600 });
  try {
    fs.chmodSync(filePath, 0o600);
  } catch {
    /* ignore */
  }
}

function loadContacts() {
  ensureDataDir();
  if (!fs.existsSync(CONTACTS_FILE)) return [];
  return JSON.parse(fs.readFileSync(CONTACTS_FILE, 'utf8')).map(normalizeContact);
}

function saveContacts(contacts) {
  ensureDataDir();
  secureWriteJson(
    CONTACTS_FILE,
    contacts.map(serializeContact),
  );
}

function markContactVerified(idOrPrefix) {
  const contact = findContact(idOrPrefix);
  if (!contact) throw new Error(ERR.CONTACT_NOT_FOUND);
  const now = Date.now();
  saveContacts(
    loadContacts().map((c) =>
      c.id === contact.id ? { ...c, verified: true, verifiedAt: now } : c,
    ),
  );
  return { ...contact, verified: true, verifiedAt: now };
}

function deleteContact(idOrPrefix) {
  const contact = findContact(idOrPrefix);
  if (!contact) throw new Error(ERR.CONTACT_NOT_FOUND);
  saveContacts(loadContacts().filter((c) => c.id !== contact.id));
}

function deleteGroup(idOrPrefix) {
  const group = findGroup(idOrPrefix);
  if (!group) throw new Error(ERR.GROUP_NOT_FOUND);
  saveGroups(loadGroups().filter((g) => g.id !== group.id));
}

function loadGroups() {
  ensureDataDir();
  if (!fs.existsSync(GROUPS_FILE)) return [];
  return JSON.parse(fs.readFileSync(GROUPS_FILE, 'utf8')).map(normalizeGroup);
}

function saveGroups(groups) {
  ensureDataDir();
  secureWriteJson(
    GROUPS_FILE,
    groups.map(serializeGroup),
  );
}

function saveAllRatchets(all) {
  ensureDataDir();
  secureWriteJson(RATCHET_FILE, all);
}

function generateGroupId() {
  return crypto.randomBytes(12).toString('base64url');
}

function deriveGroupRoomId(groupId) {
  return crypto
    .createHash('sha256')
    .update(GROUP_ROOM_SALT)
    .update(groupId)
    .digest('base64url');
}

function encodeGroupInvite(group) {
  const relay = getServerUrl().replace(/\/$/, '');
  const isLocal =
    /^https?:\/\/(localhost|10\.0\.2\.2|127\.)/.test(relay) ||
    /^http:\/\/192\.168\./.test(relay);
  const json = JSON.stringify({
    v: 2,
    gid: group.id,
    n: group.name,
    k: group.key,
    kv: group.keyVersion || 1,
    ...(group.inviteExpiresAt ? { exp: group.inviteExpiresAt } : {}),
    ...(group.members?.length ? { m: group.members } : {}),
    ...(!isLocal && relay.startsWith('http') ? { s: relay } : {}),
  });
  return 'zerorelay://group?v=1&d=' + Buffer.from(json).toString('base64url');
}

function parseGroupInvite(raw) {
  const trimmed = raw.trim();
  let dataPart = null;
  if (trimmed.startsWith('zerorelay://group?v=1&d=')) {
    dataPart = trimmed.slice('zerorelay://group?v=1&d='.length);
  } else if (trimmed.includes('zerorelay://group') && trimmed.includes('d=')) {
    dataPart = trimmed.substring(trimmed.indexOf('d=') + 2);
  }
  if (!dataPart) return null;
  try {
    const json = JSON.parse(Buffer.from(dataPart, 'base64url').toString('utf8'));
    if (json.v !== 1 && json.v !== 2) return null;
    if (!json.gid || !json.n || !json.k) return null;
    const key = Buffer.from(json.k, 'base64');
    if (key.length !== 32) return null;
    const exp = json.exp > 0 ? json.exp : null;
    if (exp && Date.now() > exp) {
      throw new Error(ERR.GROUP_INVITE_EXPIRED);
    }
    return {
      id: json.gid,
      name: json.n,
      key: json.k,
      members: Array.isArray(json.m) ? json.m : [],
      serverUrl: typeof json.s === 'string' && json.s.trim() ? json.s.trim().replace(/\/$/, '') : null,
      keyVersion: json.kv || 1,
      inviteExpiresAt: exp,
    };
  } catch (e) {
    if (e.message?.includes('过期')) throw e;
    return null;
  }
}

function createGroup(name, members = []) {
  const trimmed = name.trim();
  if (!trimmed) throw new Error(ERR.GROUP_NAME_REQUIRED);
  const id = generateGroupId();
  const key = crypto.randomBytes(32).toString('base64');
  const now = Date.now();
  const group = {
    id,
    name: trimmed,
    roomId: deriveGroupRoomId(id),
    key,
    members,
    createdAt: now,
    keyVersion: 1,
    inviteExpiresAt: now + GROUP_INVITE_TTL_MS,
    previousKeys: {},
  };
  const groups = loadGroups().filter((g) => g.id !== id);
  groups.unshift(group);
  saveGroups(groups);
  return group;
}

function rotateGroupKey(groupId) {
  const groups = loadGroups();
  const idx = groups.findIndex((g) => g.id === groupId || shortId(g.id) === groupId);
  if (idx < 0) throw new Error(ERR.GROUP_NOT_EXISTS);
  const existing = groups[idx];
  const newKey = crypto.randomBytes(32).toString('base64');
  const newVersion = (existing.keyVersion || 1) + 1;
  const now = Date.now();
  const previousKeys = { ...(existing.previousKeys || {}) };
  previousKeys[existing.keyVersion || 1] = existing.key;
  const updated = {
    ...existing,
    key: newKey,
    keyVersion: newVersion,
    inviteExpiresAt: now + GROUP_INVITE_TTL_MS,
    previousKeys,
  };
  groups.splice(idx, 1);
  groups.unshift(updated);
  saveGroups(groups);
  return updated;
}

function joinGroupFromInvite(payload) {
  if (payload.serverUrl) {
    const url = cliConfig.normalizeServerUrl(payload.serverUrl);
    cliConfig.setServerUrl(DATA_DIR, url);
    console.log(`[*] 已同步群聊服务器: ${url}`);
  }
  const group = {
    id: payload.id,
    name: payload.name,
    roomId: deriveGroupRoomId(payload.id),
    key: payload.key,
    members: payload.members || [],
    createdAt: Date.now(),
    keyVersion: payload.keyVersion || 1,
    inviteExpiresAt: payload.inviteExpiresAt || null,
    previousKeys: {},
  };
  const groups = loadGroups().filter((g) => g.id !== group.id);
  groups.unshift(group);
  saveGroups(groups);
  return group;
}

function printGroupInvite(group) {
  const payload = encodeGroupInvite(group);
  console.log(`\n群聊「${group.name}」邀请码（让对方扫码或粘贴加入）:\n`);
  return new Promise((resolve) => {
    qrcodeTerminal.generate(payload, { small: true }, (code) => {
      console.log(code);
      console.log('\n链接:');
      console.log(payload);
      console.log(`\n群 ID: ${group.id}`);
      console.log('');
      resolve();
    });
  });
}

function findGroup(idOrPrefix) {
  return loadGroups().find((g) => g.id === idOrPrefix || g.id.startsWith(idOrPrefix));
}

function findContact(idOrPrefix) {
  return loadContacts().find((c) => c.id === idOrPrefix || c.id.startsWith(idOrPrefix));
}

function shortId(id) {
  return id.length > 10 ? `${id.slice(0, 8)}…` : id;
}

function resolveSenderLabel(senderId, mySenderId, contacts, group) {
  if (senderId === mySenderId) return '你';
  if (group?.members?.length) {
    for (const memberId of group.members) {
      const contact = contacts.find((c) => c.id === memberId);
      if (contact && senderIdFromPublicKey(contact.publicKey) === senderId) {
        return contact.name;
      }
    }
  }
  const short = senderId.replace(/^id_/, '');
  return short.length > 10 ? `id_${short.slice(0, 6)}` : senderId;
}

function printGroupSummary(group, contacts) {
  const memberNames = (group.members || [])
    .map((id) => contacts.find((c) => c.id === id)?.name || shortId(id))
    .filter(Boolean);
  const membersText =
    memberNames.length > 0 ? memberNames.join('、') : '（成员通过邀请加入，未绑定通讯录）';
  console.log(`  [群] ${group.name}  id=${shortId(group.id)}  成员: ${membersText}`);
}

async function promptLine(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const answer = await new Promise((resolve) => rl.question(question, resolve));
  rl.close();
  return answer;
}

async function runChatSession({
  title,
  metaLines = [],
  roomId,
  sessionKey,
  senderId,
  promptLabel,
  helpLines = [],
  onInvite,
  localPublicKey = null,
  peerPublicKey = null,
  identity = null,
  roomAccessToken = null,
  signingKeys = null,
  peerVerified = true,
}) {
  console.log(`\n[*] ${title}`);
  metaLines.forEach((line) => console.log(`[*] ${line}`));
  console.log(
    '[*] 命令: /help  /quit  /detach' + (onInvite ? '  /invite' : '') + '  （/detach 后台监听）',
  );
  console.log('─'.repeat(50));

  const buildWatchSnapshot = () => ({
    title,
    roomId,
    sessionKeyB64: Buffer.isBuffer(sessionKey)
      ? sessionKey.toString('base64')
      : Buffer.from(sessionKey).toString('base64'),
    senderId,
    roomAccessToken,
    isGroupChat,
    localPublicKey,
    peerPublicKey,
    groupId: group?.id || null,
  });

  let lastTimestamp = 0;
  let polling = true;
  const legacyFlag = { value: false };
  const seenIds = new Set();
  const contacts = loadContacts();
  const group = onInvite ? loadGroups().find((g) => g.roomId === roomId) : null;
  const isGroupChat = !!group;
  const groupKeys = group ? groupKeysByVersion(group) : null;
  const groupKv = group?.keyVersion || 1;

  const formatPrefix = (sid) => {
    const label = resolveSenderLabel(sid, senderId, contacts, group);
    return sid === senderId ? '[你]' : `[${label}]`;
  };

  const ingest = createInboundProcessor({
    seenIds,
    formatPrefix,
    isGroupChat,
    groupKeys,
    roomId,
    roomAccessToken,
    sessionKey,
    localPublicKey,
    peerPublicKey,
    protocol,
    legacyFlag,
    deps: { ratchetDecrypt, decryptGroupMessage },
  });

  const applyInbound = (msg, { notify = false } = {}) => {
    const out = ingest(msg);
    if (!out) return;
    console.log(out.line);
    if (notify && msg.senderId !== senderId) {
      terminalNotify(title, out.line.replace(/^\d{1,2}:\d{2}:\d{2}\s+/, ''));
    }
    if (out.timestamp) lastTimestamp = Math.max(lastTimestamp, out.timestamp);
  };

  let relayWs = null;
  if (roomAccessToken) {
    relayWs = new RelayWebSocket({
      serverUrl: getServerUrl(),
      dataDir: DATA_DIR,
      roomId,
      senderId,
      roomAccessToken,
      onMessage: applyInbound,
      onStatus: (status, detail) => {
        if (status === 'connected') {
          console.log('[*] WebSocket 已连接（轮询仍作备用）');
        } else if (status === 'error' && detail) {
          console.log(`[!] WebSocket: ${detail}`);
        }
      },
    });
    relayWs.connect();
  }

  const pollMessages = async () => {
    while (polling) {
      try {
        const { frame } = relayTunnel.encodeTunnelFrame(roomAccessToken, {
          op: 'poll',
          since: lastTimestamp,
          timeout: protocol.POLL_TIMEOUT_MS,
        });
        const result = await request('/t', 'POST', frame, null, 'application/octet-stream');
        const gotMessages = result.success && result.messages?.length > 0;
        if (gotMessages) {
          for (const msg of result.messages) {
            applyInbound(msg);
          }
        }
        protocol.notePollResult(gotMessages);
      } catch {
        protocol.notePollResult(false);
      }
      const delay =
        relayWs?.isConnected?.() ? protocol.POLL_WS_CONNECTED_MS : protocol.pollDelayMs();
      await new Promise((r) => setTimeout(r, delay));
    }
  };

  pollMessages();
  watchSessionStore.saveWatchSession(DATA_DIR, buildWatchSnapshot());

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const question = (p) => new Promise((r) => rl.question(p, r));

  while (true) {
    const text = await question(promptLabel);
    const cmd = text.trim();
    if (cmd === '/quit' || cmd === '/q') {
      polling = false;
      relayWs?.close();
      watchSessionStore.clearWatchSession(DATA_DIR);
      console.log('\n[*] 已退出聊天');
      rl.close();
      return;
    }
    if (cmd === '/detach' || cmd === '/bg') {
      polling = false;
      relayWs?.close();
      watchSessionStore.saveWatchSession(DATA_DIR, buildWatchSnapshot());
      const pid = watchSessionStore.startWatchDaemon(DATA_DIR);
      console.log(`\n[*] 已转入后台监听 (pid ${pid})`);
      console.log('[*] 日志:', watchSessionStore.watchLogPath(DATA_DIR));
      console.log('[*] 停止: node cli.js watch stop');
      rl.close();
      return;
    }
    if (cmd === '/help' || cmd === '/h') {
      console.log('\n可用命令:');
      helpLines.forEach((line) => console.log(`  ${line}`));
      console.log('  /quit    退出并停止后台监听');
      console.log('  /detach  后台继续收消息（响铃/终端通知）');
      if (onInvite) console.log('  /invite  显示群邀请二维码');
      console.log('');
      continue;
    }
    if (cmd === '/invite' && onInvite) {
      rl.pause();
      await onInvite();
      rl.resume();
      continue;
    }
    if (cmd.startsWith('/')) {
      console.log('[-] 未知命令，输入 /help 查看');
      continue;
    }
    if (cmd) {
      if (!isGroupChat && !peerVerified) {
        console.log(`[-] ${ERR.VERIFY_BEFORE_SEND}`);
        continue;
      }
      const validated = validateOutgoingMessage(cmd);
      if (!validated.ok) {
        if (validated.error) console.log(`[-] ${validated.error}`);
        continue;
      }
      const payload = isGroupChat
        ? encryptGroupMessage(validated.text, sessionKey, groupKv)
        : ratchetEncrypt(roomId, sessionKey, localPublicKey, peerPublicKey, validated.text);
      const now = Date.now();
      const localId = `local-${now}`;
      seenIds.add(localId);
      const time = new Date(now).toLocaleTimeString();
      console.log(`${time} ${formatPrefix(senderId)}: ${validated.text}`);
      try {
        const routeId = relayCrypto.routeIdFromToken(roomAccessToken);
        const inner = {
          op: 'send',
          ciphertext: payload.ciphertext,
          iv: payload.iv,
          tag: payload.tag,
          senderPk: identity.publicKey,
          signPk: signingKeys.signPublic,
          sig: relayCrypto.signMessage(
            signingKeys,
            routeId,
            senderId,
            now,
            payload.ciphertext,
            payload.iv,
            payload.tag,
          ),
          timestamp: now,
        };
        const { frame } = relayTunnel.encodeTunnelFrame(roomAccessToken, inner);
        const res = await request('/t', 'POST', frame, null, 'application/octet-stream');
        if (res && res.success === false) {
          console.log(`[-] ${ERR.SEND_FAILED}`);
        }
      } catch (e) {
        console.log(`[-] ${ERR.SEND_FAILED}:`, e.message);
      }
    }
  }
}

function contactIdFromPublicKey(pubB64) {
  const hash = crypto.createHash('sha256').update(Buffer.from(pubB64, 'base64')).digest();
  return hash.slice(0, 8).toString('hex');
}

function fingerprint(pubB64) {
  const hash = crypto.createHash('sha256').update(Buffer.from(pubB64, 'base64')).digest();
  return Array.from(hash.slice(0, 8))
    .map((b) => b.toString(16).padStart(2, '0').toUpperCase())
    .join('-');
}

function deriveRoomId(pubB64A, pubB64B) {
  const a = Buffer.from(pubB64A, 'base64');
  const b = Buffer.from(pubB64B, 'base64');
  const sorted = [a, b].sort((x, y) => Buffer.compare(x, y));
  return crypto
    .createHash('sha256')
    .update(Buffer.concat(sorted))
    .digest('base64url');
}

function deriveSessionKey(privateKeyB64, peerPublicKeyB64, roomId) {
  const privateKey = Buffer.from(privateKeyB64, 'base64');
  const peerPublic = Buffer.from(peerPublicKeyB64, 'base64');
  if (privateKey.length !== 32 || peerPublic.length !== 32) {
    throw new Error('密钥长度无效');
  }
  const shared = x25519.getSharedSecret(privateKey, peerPublic);
  return crypto.hkdfSync('sha256', Buffer.from(shared), roomId, HKDF_INFO, 32);
}

/** HKDF-SHA256，与 Android MessageRatchet 一致 */
function hkdfExpand(ikm, salt, info, len = 32) {
  return crypto.hkdfSync('sha256', ikm, salt, Buffer.from(info, 'utf8'), len);
}

function loadAllRatchets() {
  ensureDataDir();
  if (!fs.existsSync(RATCHET_FILE)) return {};
  try {
    return JSON.parse(fs.readFileSync(RATCHET_FILE, 'utf8'));
  } catch {
    return {};
  }
}


function loadRatchetState(roomId) {
  return loadAllRatchets()[roomId] || null;
}

function saveRatchetState(roomId, state) {
  const all = loadAllRatchets();
  all[roomId] = state;
  saveAllRatchets(all);
}

function localIsLowerPublicKey(localPubB64, peerPubB64) {
  return Buffer.compare(Buffer.from(localPubB64, 'base64'), Buffer.from(peerPubB64, 'base64')) <= 0;
}

/** 字典序较小一方用 chain-send-v2 作为 send，与 Android MessageRatchet 一致 */
function ratchetFromRoot(rootKey, roomId, localPubB64, peerPubB64) {
  const salt = Buffer.from(roomId, 'utf8');
  const lower = localIsLowerPublicKey(localPubB64, peerPubB64);
  const sendInfo = lower ? 'chain-send-v2' : 'chain-recv-v2';
  const recvInfo = lower ? 'chain-recv-v2' : 'chain-send-v2';
  return {
    send: hkdfExpand(rootKey, salt, sendInfo).toString('base64'),
    recv: hkdfExpand(rootKey, salt, recvInfo).toString('base64'),
    ss: 0,
    rs: 0,
  };
}

function ratchetStepChain(chainB64) {
  const chainKey = Buffer.from(chainB64, 'base64');
  return {
    messageKey: hkdfExpand(chainKey, chainKey, 'message'),
    nextChain: hkdfExpand(chainKey, chainKey, 'chain').toString('base64'),
  };
}

function parseV2Envelope(plain) {
  try {
    const json = JSON.parse(plain);
    if (json.v === PROTOCOL_V2 && typeof json.t === 'string') return json;
  } catch {
    /* legacy plaintext */
  }
  return null;
}

function parseLegacyPlain(plain) {
  const env = parseV2Envelope(plain);
  if (env) return env.t;
  return plain;
}

/** 私聊 v2 棘轮加密（与 Android MessageCipher.encryptDirect 对齐） */
function ratchetEncrypt(roomId, rootKey, localPubB64, peerPubB64, plaintext) {
  let state = loadRatchetState(roomId);
  if (!state) state = ratchetFromRoot(rootKey, roomId, localPubB64, peerPubB64);
  const { messageKey, nextChain } = ratchetStepChain(state.send);
  state.send = nextChain;
  const seq = state.ss;
  state.ss += 1;
  const envelope = JSON.stringify({ v: PROTOCOL_V2, s: seq, t: protocol.padPlaintext(plaintext) });
  const payload = encrypt(envelope, messageKey);
  saveRatchetState(roomId, state);
  return payload;
}

/** 私聊 v2 棘轮解密；失败时回退静态会话密钥（旧消息，受截止时间限制） */
function ratchetDecrypt(roomId, rootKey, localPubB64, peerPubB64, ciphertext, iv, tag, senderId, messageTimestamp = 0) {
  const ok = (text, usedLegacyStaticKey = false) =>
    text != null ? { text, usedLegacyStaticKey } : null;

  let state = loadRatchetState(roomId);
  if (!state) state = ratchetFromRoot(rootKey, roomId, localPubB64, peerPubB64);

  const { messageKey, nextChain } = ratchetStepChain(state.recv);
  let plain = null;
  try {
    plain = decrypt(ciphertext, iv, tag, messageKey, senderId);
  } catch {
    plain = null;
  }
  if (plain != null) {
    const env = parseV2Envelope(plain);
    if (env) {
      const seq = env.s;
      if (seq < state.rs) {
        saveRatchetState(roomId, state);
        return ok(protocol.unwrapPlaintext(env.t));
      }
      if (seq === state.rs) {
        state.recv = nextChain;
        state.rs += 1;
        saveRatchetState(roomId, state);
        return ok(protocol.unwrapPlaintext(env.t));
      }
    } else {
      state.recv = nextChain;
      state.rs += 1;
      saveRatchetState(roomId, state);
      return ok(protocol.unwrapPlaintext(plain));
    }
  }

  if (!protocol.allowsLegacyStaticFallback(messageTimestamp)) {
    return null;
  }
  try {
    const legacy = parseLegacyPlain(decrypt(ciphertext, iv, tag, rootKey, senderId));
    return ok(legacy ? protocol.unwrapPlaintext(legacy) : null, true);
  } catch {
    return null;
  }
}

/** 群聊 v2 信封（kv = 密钥版本） */
function encryptGroupMessage(plaintext, groupKey, keyVersion = 1) {
  const envelope = JSON.stringify({
    v: PROTOCOL_V2,
    kv: keyVersion,
    t: protocol.padPlaintext(plaintext),
  });
  return encrypt(envelope, groupKey);
}

function groupKeysByVersion(group) {
  const map = new Map();
  map.set(group.keyVersion || 1, Buffer.from(group.key, 'base64'));
  const prev = group.previousKeys || {};
  for (const [ver, b64] of Object.entries(prev)) {
    map.set(parseInt(ver, 10), Buffer.from(b64, 'base64'));
  }
  return map;
}

function decryptGroupMessage(ciphertext, iv, tag, keysByVersion, senderId) {
  for (const key of keysByVersion.values()) {
    let plain;
    try {
      plain = decrypt(ciphertext, iv, tag, key, senderId);
    } catch {
      continue;
    }
    const env = parseV2Envelope(plain);
    if (env) {
      const kv = env.kv ?? 1;
      if (keysByVersion.has(kv)) return protocol.unwrapPlaintext(env.t);
    } else if (plain) {
      const legacy = parseLegacyPlain(plain);
      return legacy ? protocol.unwrapPlaintext(legacy) : null;
    }
  }
  return null;
}

function senderIdFromPublicKey(pubB64) {
  const hash = crypto.createHash('sha256').update(Buffer.from(pubB64, 'base64')).digest();
  return 'id_' + hash.slice(0, 6).toString('base64url').replace(/-/g, 'x').replace(/_/g, 'y');
}

function encodeContactPayload(publicKeyB64, name) {
  const json = JSON.stringify({ v: 1, pk: publicKeyB64, ...(name ? { n: name } : {}) });
  return 'zerorelay://v1?d=' + Buffer.from(json).toString('base64url');
}

function parseContactPayload(raw) {
  const trimmed = raw.trim();
  let jsonStr;
  if (trimmed.startsWith('zerorelay://v1?d=')) {
    jsonStr = Buffer.from(trimmed.slice('zerorelay://v1?d='.length), 'base64url').toString('utf8');
  } else if (trimmed.startsWith('{')) {
    jsonStr = trimmed;
  } else {
    const key = Buffer.from(trimmed, 'base64');
    if (key.length !== 32) throw new Error('公钥无效');
    return { publicKey: key.toString('base64'), name: null };
  }
  const json = JSON.parse(jsonStr);
  if (json.v !== 1 || !json.pk) throw new Error('二维码无效');
  const key = Buffer.from(json.pk, 'base64');
  if (key.length !== 32) throw new Error('公钥无效');
  return { publicKey: json.pk, name: json.n || null };
}

async function addContact(raw) {
  const groupPayload = parseGroupInvite(raw);
  if (groupPayload) {
    const group = joinGroupFromInvite(groupPayload);
    console.log(`[+] 已加入群聊: ${group.name} (${group.id})`);
    await offerEnterGroupChat(group);
    return;
  }
  const identity = loadOrCreateIdentity();
  const { publicKey, name } = parseContactPayload(raw);
  if (publicKey === identity.publicKey) throw new Error('不能添加自己的公钥');
  const id = contactIdFromPublicKey(publicKey);
  const displayName = name || fingerprint(publicKey);
  const contacts = loadContacts().filter((c) => c.id !== id);
  contacts.unshift({
    id,
    name: displayName,
    publicKey,
    addedAt: Date.now(),
    verified: false,
    verifiedAt: null,
  });
  saveContacts(contacts);
  console.log(`[+] 已添加联系人: ${displayName} (${id})`);
  console.log(`[*] 安全码: ${fingerprint(publicKey)}  核对后: node cli.js contact verify ${shortId(id)}`);
}

function encrypt(text, key) {
  previewText(text);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const encrypted = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  if (CRYPTO_LOG) {
    cryptoLog(
      `加密 plain=${Buffer.byteLength(text, 'utf8')}B → cipher=${encrypted.length}B iv=12B (AES-GCM) key#=${keyFingerprint(key)}`,
    );
  }
  return {
    ciphertext: encrypted.toString('base64'),
    iv: iv.toString('base64'),
    tag: tag.toString('base64'),
  };
}

function decrypt(ciphertext, iv, tag, key, senderId = '?') {
  const cipherBuf = Buffer.from(ciphertext, 'base64');
  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64'));
    decipher.setAuthTag(Buffer.from(tag, 'base64'));
    const decrypted = Buffer.concat([
      decipher.update(cipherBuf),
      decipher.final(),
    ]);
    const plain = decrypted.toString('utf8');
    if (CRYPTO_LOG) {
      cryptoLog(`解密成功 from=${senderId} cipher=${cipherBuf.length}B → plain=${plain.length}B`);
    }
    previewText(plain);
    return plain;
  } catch (e) {
    if (CRYPTO_LOG) cryptoLog(`解密失败 from=${senderId} reason=${e.message || e}`);
    throw e;
  }
}

function request(path, method = 'GET', body = null, routeHashB64 = null, contentType = 'application/json') {
  return new Promise((resolve, reject) => {
    const reqUrl = new URL(path, getServerUrl());
    const lib = reqUrl.protocol === 'https:' ? https : http;
    const headers = { 'Content-Type': contentType };
    if (routeHashB64) headers['X-Route-Hash'] = routeHashB64;

    const options = {
      hostname: reqUrl.hostname,
      port: reqUrl.port || (reqUrl.protocol === 'https:' ? 443 : 80),
      path: reqUrl.pathname + reqUrl.search,
      method,
      headers,
    };

    if (reqUrl.protocol === 'https:') {
      options.checkServerIdentity = (host, cert) => {
        const evalResult = serverHealth.evaluatePin(DATA_DIR, host, cert);
        if (!evalResult.trusted && evalResult.newPin) {
          cliConfig.setPendingTlsPin(DATA_DIR, host, evalResult.newPin);
          throw new Error(
            `TLS 证书 pin 不匹配 (${host})，新 pin: ${evalResult.newPin}\n` +
              '执行: node cli.js config trust-pin',
          );
        }
        if (evalResult.tofu && evalResult.newPin) {
          identityStore.rememberTlsPin(DATA_DIR, host, evalResult.newPin);
          if (CRYPTO_LOG) cryptoLog(`TOFU 已记录 TLS pin ${host}: ${evalResult.newPin}`);
        }
        return undefined;
      };
    }

    const req = lib.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch {
          resolve({ error: 'Invalid JSON response' });
        }
      });
    });
    req.on('error', reject);
    req.setTimeout(5000, () => {
      req.destroy();
      reject(new Error('Request timeout'));
    });
    const payload = Buffer.isBuffer(body) ? body : body ? JSON.stringify(body) : null;
    if (payload && payload.length > 64 * 1024) {
      reject(new Error('request body too large'));
      return;
    }
    if (payload) req.write(payload);
    req.end();
  });
}

async function chatWithGroup(group) {
  const identity = loadOrCreateIdentity();
  const sessionKey = Buffer.from(group.key, 'base64');
  const senderId = senderIdFromPublicKey(identity.publicKey);
  const signingKeys = relayCrypto.deriveSigningKeys(identity.privateKey);
  const roomAccessToken = relayCrypto.deriveRoomAccessToken(sessionKey, group.roomId);

  if (isGroupExpired(group)) {
    console.log(`[-] ${ERR.GROUP_EXPIRED}`);
    console.log('[*] 执行: node cli.js group rotate <群id>');
    return;
  }

  touchServerUsage();
  cliConfig.addRecentRoom(DATA_DIR, group.roomId);

  if (CRYPTO_LOG) {
    const roomShort = group.roomId.length > 16 ? `${group.roomId.slice(0, 16)}…` : group.roomId;
    cryptoLog(
      `群聊会话 room=${roomShort} key#=${keyFingerprint(sessionKey)} kv=${group.keyVersion || 1} (v2 信封)`,
    );
  }

  await runChatSession({
    title: `群聊 · ${group.name}`,
    metaLines: [`你的 ID: ${senderId}`, `群 ID: ${shortId(group.id)}`],
    roomId: group.roomId,
    sessionKey,
    senderId,
    identity,
    roomAccessToken,
    signingKeys,
    promptLabel: `[${group.name}]> `,
    helpLines: ['直接输入文字发送消息'],
    onInvite: () => printGroupInvite(group),
  });
}

async function chatWithContact(contact, { allowUnverified = false } = {}) {
  const identity = loadOrCreateIdentity();
  touchServerUsage();
  cliConfig.addRecentContact(DATA_DIR, contact.id);
  cliConfig.addRecentRoom(DATA_DIR, deriveRoomId(identity.publicKey, contact.publicKey));

  if (!contact.verified && !allowUnverified) {
    console.log(`[!] 联系人「${contact.name}」尚未核对安全码，已拒绝进入聊天`);
    console.log(`[*] 对方安全码: ${fingerprint(contact.publicKey)}`);
    console.log(`[*] 核对后执行: node cli.js contact verify ${shortId(contact.id)}`);
    return;
  }
  const roomId = deriveRoomId(identity.publicKey, contact.publicKey);
  const sessionKey = deriveSessionKey(identity.privateKey, contact.publicKey, roomId);
  const senderId = senderIdFromPublicKey(identity.publicKey);

  if (CRYPTO_LOG) {
    const roomShort = roomId.length > 16 ? `${roomId.slice(0, 16)}…` : roomId;
    cryptoLog(
      `会话密钥派生 room=${roomShort} key#=${keyFingerprint(sessionKey)} (X25519+HKDF + v2 棘轮)`,
    );
  }

  const signingKeys = relayCrypto.deriveSigningKeys(identity.privateKey);
  const roomAccessToken = relayCrypto.deriveRoomAccessToken(sessionKey, roomId);

  await runChatSession({
    title: `与 ${contact.name} 单聊`,
    metaLines: [`你的 ID: ${senderId}`, `对方安全码: ${fingerprint(contact.publicKey)}`],
    roomId,
    sessionKey,
    senderId,
    localPublicKey: identity.publicKey,
    peerPublicKey: contact.publicKey,
    identity,
    roomAccessToken,
    signingKeys,
    peerVerified: contact.verified,
    promptLabel: `[${contact.name}]> `,
    helpLines: ['直接输入文字发送消息'],
  });
}

async function pickMembersFromContacts() {
  const contacts = loadContacts();
  if (contacts.length === 0) {
    console.log('[*] 暂无联系人，可稍后用邀请码拉人进群');
    return [];
  }
  console.log('\n可选成员（输入编号，逗号分隔，回车跳过）:');
  contacts.forEach((c, i) => {
    console.log(`  [${i + 1}] ${c.name}  (${shortId(c.id)})`);
  });
  const raw = await promptLine('成员编号: ');
  if (!raw.trim()) return [];
  const picked = new Set();
  for (const part of raw.split(/[,，\s]+/)) {
    const idx = parseInt(part, 10) - 1;
    if (idx >= 0 && idx < contacts.length) picked.add(contacts[idx].id);
  }
  return [...picked];
}

async function offerEnterGroupChat(group) {
  const answer = await promptLine('\n按回车进入群聊，输入 n 跳过: ');
  if (answer.trim().toLowerCase() === 'n') return;
  await chatWithGroup(group);
}

function printInteractiveMenuHeader(identity, contacts, groups, cfg) {
  console.log('\n── ZeroRelay 主菜单 ──');
  console.log('Server:', getServerUrl());
  console.log('我的安全码:', fingerprint(identity.publicKey));
  if (watchSessionStore.isWatchRunning(DATA_DIR)) {
    const pid = watchSessionStore.getWatchPid(DATA_DIR);
    console.log(`[*] 后台监听运行中 (pid ${pid}) — watch stop 可停止`);
  }
  if (cfg.recentContactIds?.length) {
    const recent = cfg.recentContactIds
      .map((id) => contacts.find((c) => c.id === id))
      .filter(Boolean)
      .slice(0, 3);
    if (recent.length) {
      console.log('最近联系人:', recent.map((c) => c.name).join('、'));
    }
  }
  console.log('\n快捷: [a]添加  [q]二维码  [c]配置  [w]后台  [h]帮助  [数字]聊天  [g数字]群  [回车]退出');
}

async function runInteractiveMainMenu(identity) {
  while (true) {
    const contacts = loadContacts();
    const groups = loadGroups();
    const cfg = cliConfig.loadConfig(DATA_DIR);
    printInteractiveMenuHeader(identity, contacts, groups, cfg);

    if (contacts.length === 0 && groups.length === 0) {
      console.log('\n暂无联系人或群聊。输入 a 添加，q 显示二维码。');
    } else {
      console.log('\n── 联系人 ──');
      if (contacts.length === 0) console.log('  （暂无）');
      else {
        contacts.forEach((c, i) => {
          const flag = c.verified ? '' : ' [未验证]';
          console.log(`  [${i + 1}] ${c.name}${flag}  id=${shortId(c.id)}`);
        });
      }
      console.log('\n── 群聊 ──');
      if (groups.length === 0) console.log('  （暂无）');
      else {
        groups.forEach((g, i) => {
          printGroupSummary(g, contacts);
          console.log(`       → g${i + 1}`);
        });
      }
    }

    const pick = await promptLine('\n选择: ');
    const trimmed = pick.trim().toLowerCase();
    if (!trimmed) {
      console.log('再见。');
      return;
    }
    if (trimmed === 'a') {
      const raw = await promptLine('粘贴邀请内容: ');
      if (raw.trim()) await addContact(raw);
      continue;
    }
    if (trimmed === 'q') {
      const payload = encodeContactPayload(identity.publicKey, null);
      console.log('\n安全码:', fingerprint(identity.publicKey));
      qrcodeTerminal.generate(payload, { small: true }, (code) => {
        console.log(code);
        console.log('\n', payload, '\n');
      });
      const pngAns = await promptLine('保存 PNG 路径（回车跳过）: ');
      if (pngAns.trim()) {
        try {
          const out = await writeQrPng(payload, pngAns.trim());
          console.log(`[+] 已保存 ${out}`);
        } catch (e) {
          console.log('[-]', e.message);
        }
      }
      continue;
    }
    if (trimmed === 'c') {
      console.log('当前:', getServerUrl());
      const url = await promptLine('新服务器 URL（回车跳过）: ');
      if (url.trim()) {
        cliConfig.setServerUrl(DATA_DIR, cliConfig.normalizeServerUrl(url));
        console.log('[+] 已保存');
      }
      continue;
    }
    if (trimmed === 'w') {
      const sess = watchSessionStore.loadWatchSession(DATA_DIR);
      if (!sess) {
        console.log('[-] 无后台会话。先在聊天里 /detach，或先进入一次聊天');
        continue;
      }
      if (watchSessionStore.isWatchRunning(DATA_DIR)) {
        console.log('[*] 已在运行，日志:', watchSessionStore.watchLogPath(DATA_DIR));
      } else {
        const pid = watchSessionStore.startWatchDaemon(DATA_DIR);
        console.log(`[+] 已启动后台监听 pid ${pid}`);
      }
      continue;
    }
    if (trimmed === 'h') {
      printHelp();
      continue;
    }
    if (trimmed.startsWith('g')) {
      const idx = parseInt(trimmed.slice(1), 10) - 1;
      if (idx < 0 || idx >= groups.length) {
        console.log('[-] 无效群聊编号');
        continue;
      }
      await chatWithGroup(groups[idx]);
      continue;
    }
    const idx = parseInt(trimmed, 10) - 1;
    if (idx < 0 || idx >= contacts.length) {
      console.log('[-] 无效编号，输入 h 查看帮助');
      continue;
    }
    await chatWithContact(contacts[idx]);
  }
}

async function runWatchLoop() {
  const session = watchSessionStore.loadWatchSession(DATA_DIR);
  if (!session) {
    console.error('[-] 无 watch-session.json，请先在聊天中使用 /detach');
    process.exit(1);
  }
  console.log(`[*] 后台监听: ${session.title}`);

  let lastTimestamp = 0;
  const legacyFlag = { value: false };
  const seenIds = new Set();
  const contacts = loadContacts();
  const group = session.isGroupChat
    ? loadGroups().find((g) => g.roomId === session.roomId)
    : null;
  const sessionKey = Buffer.from(session.sessionKeyB64, 'base64');
  const groupKeys = group ? groupKeysByVersion(group) : null;

  const formatPrefix = (sid) => {
    if (sid === session.senderId) return '你';
    return resolveSenderLabel(sid, session.senderId, contacts, group);
  };

  const ingest = createInboundProcessor({
    seenIds,
    formatPrefix,
    isGroupChat: !!session.isGroupChat,
    groupKeys,
    roomId: session.roomId,
    roomAccessToken: session.roomAccessToken,
    sessionKey,
    localPublicKey: session.localPublicKey,
    peerPublicKey: session.peerPublicKey,
    protocol,
    legacyFlag,
    deps: { ratchetDecrypt, decryptGroupMessage },
  });

  const onMessage = (msg) => {
    if (msg.senderId === session.senderId) return;
    const out = ingest(msg);
    if (!out) return;
    const line = `[watch] ${out.line}`;
    console.log(line);
    try {
      fs.appendFileSync(watchSessionStore.watchLogPath(DATA_DIR), `${line}\n`);
    } catch {
      /* ignore */
    }
    terminalNotify(session.title, out.line.replace(/^\d{1,2}:\d{2}:\d{2}\s+/, ''));
    if (out.timestamp) lastTimestamp = Math.max(lastTimestamp, out.timestamp);
  };

  let relayWs = null;
  if (session.roomAccessToken) {
    relayWs = new RelayWebSocket({
      serverUrl: getServerUrl(),
      dataDir: DATA_DIR,
      roomId: session.roomId,
      senderId: session.senderId,
      roomAccessToken: session.roomAccessToken,
      onMessage,
    });
    relayWs.connect();
  }

  while (true) {
    try {
      const { frame } = relayTunnel.encodeTunnelFrame(session.roomAccessToken, {
        op: 'poll',
        since: lastTimestamp,
        timeout: protocol.POLL_TIMEOUT_MS,
      });
      const result = await request('/t', 'POST', frame, null, 'application/octet-stream');
      const got = result.success && result.messages?.length > 0;
      if (got) {
        for (const msg of result.messages) onMessage(msg);
      }
      protocol.notePollResult(got);
    } catch (e) {
      console.error('[watch] 轮询错误:', e.message || e);
      protocol.notePollResult(false);
    }
    const delay = relayWs?.isConnected?.() ? protocol.POLL_WS_CONNECTED_MS : protocol.pollDelayMs();
    await new Promise((r) => setTimeout(r, delay));
  }
}

function printHelp() {
  console.log(`
ZeroRelay CLI

用法: zerorelay [服务器URL] <命令>   （或 node cli.js / npm run cli --）

联系人:
  contact list                 列出联系人
  contact verify <id>          标记已核对安全码
  contact rm <id>              删除联系人
  add [payload]                添加联系人 / 加入群
  chat <id>                    进入单聊
  qr                           显示我的二维码

群聊:
  group list                   列出群聊
  group create <名称>          创建群聊
  group invite <群id>          显示邀请码
  group join [payload]         加入群聊
  group chat <群id>            进入群聊
  group rotate <群id>          轮换群密钥
  group rm <群id>              删除群聊

配置:
  config show                  显示当前配置
  config set server <url>      保存默认中继地址
  config test [url]            测试连接（HTTPS 校验证书 pin）
  config trust-pin             信任上次测试提示的新 pin

棘轮备份:
  ratchet export               导出到 ~/.zero-relay/ratchet-backup.enc.json
  ratchet import               从上述文件导入
  ratchet export --clipboard   导出到剪贴板（macOS pbcopy）
  ratchet import --clipboard   从剪贴板导入（macOS pbpaste）

后台监听:
  watch                        前台运行监听（或守护进程入口）
  watch status                 查看后台会话与 pid
  watch stop                   停止后台守护进程
  聊天内 /detach               退出界面并自动启动后台监听

二维码:
  qr --png <路径>              保存 PNG（需 npm install qrcode）

打包:
  npm run build:cli            生成 dist/ 下单文件可执行（需 dev 依赖 pkg）

无参数进入交互主菜单。服务器 URL 也可写在 config（config set server）。
`);
}

async function main() {
  const args = process.argv.slice(2).filter((a) => !a.startsWith('http'));
  const identity = loadOrCreateIdentity();

  if (args[0] === 'help' || args[0] === '--help' || args[0] === '-h') {
    printHelp();
    return;
  }

  if (args[0] === 'contact' && args[1] === 'list') {
    const contacts = loadContacts();
    if (contacts.length === 0) {
      console.log('暂无联系人');
      return;
    }
    contacts.forEach((c, i) => {
      const flag = c.verified ? '已核对' : '未验证';
      console.log(
        `${i + 1}. ${c.name}  [${flag}]  id=${c.id}\n   安全码 ${fingerprint(c.publicKey)}`,
      );
    });
    return;
  }

  if (args[0] === 'contact' && args[1] === 'verify' && args[2]) {
    try {
      const c = markContactVerified(args[2]);
      console.log(`[+] ${ERR.CONTACT_VERIFIED}: ${c.name}`);
    } catch (e) {
      console.log('[-]', e.message);
      process.exit(1);
    }
    return;
  }

  if (args[0] === 'contact' && (args[1] === 'rm' || args[1] === 'delete') && args[2]) {
    try {
      const c = findContact(args[2]);
      deleteContact(args[2]);
      console.log(`[+] 已删除联系人: ${c.name}`);
    } catch (e) {
      console.log('[-]', e.message);
      process.exit(1);
    }
    return;
  }

  if (args[0] === 'config' && args[1] === 'show') {
    const cfg = cliConfig.loadConfig(DATA_DIR);
    console.log('默认服务器:', cfg.serverUrl || '(未设置，使用启动参数或 http://localhost:8787)');
    console.log('当前会话 URL:', getServerUrl());
    if (cfg.pendingTlsPin) {
      console.log(
        `待信任 TLS pin: ${cfg.pendingTlsPin.pin} (${cfg.pendingTlsPin.host}) — 执行 config trust-pin`,
      );
    }
    if (cfg.recentContactIds?.length) {
      console.log('\n最近联系人:');
      const contacts = loadContacts();
      cfg.recentContactIds.forEach((id) => {
        const c = contacts.find((x) => x.id === id);
        console.log(`  - ${c ? c.name : id} (${shortId(id)})`);
      });
    }
    if (cfg.recentRoomIds?.length) {
      console.log('\n最近房间:');
      cfg.recentRoomIds.forEach((rid) => console.log(`  - ${shortId(rid)}`));
    }
    console.log(
      '\n调试日志: CRYPTO_LOG=1 node cli.js …  （对齐 Android Debug 包 Logcat 标签 ZeroRelay.Crypto）',
    );
    console.log('明文预览: CRYPTO_LOG_PLAINTEXT=1');
    return;
  }

  if (args[0] === 'config' && args[1] === 'set' && args[2] === 'server' && args[3]) {
    const url = cliConfig.normalizeServerUrl(args.slice(3).join(' '));
    if (!url) {
      console.log(`[-] ${ERR.SERVER_REQUIRED}`);
      process.exit(1);
    }
    cliConfig.setServerUrl(DATA_DIR, url);
    console.log(`[+] 已保存服务器: ${url}`);
    return;
  }

  if (args[0] === 'config' && args[1] === 'test') {
    const url = args[2] ? cliConfig.normalizeServerUrl(args.slice(2).join(' ')) : getServerUrl();
    try {
      const result = await serverHealth.checkServer(DATA_DIR, url);
      cliConfig.clearPendingTlsPin(DATA_DIR);
      console.log(`[+] 连接成功: ${result.normalizedUrl}`);
      if (result.pinned) console.log('[*] TLS 证书已 pin');
    } catch (e) {
      if (e.name === 'CertificatePinMismatchError' && e.newPin) {
        const host = new URL(url).hostname;
        cliConfig.setPendingTlsPin(DATA_DIR, host, e.newPin);
        console.log('[-] 服务器证书已变更');
        console.log(`[*] 新 pin: ${e.newPin}`);
        console.log('[*] 确认信任: node cli.js config trust-pin');
        process.exit(1);
      }
      console.log('[-]', e.message || e);
      process.exit(1);
    }
    return;
  }

  if (args[0] === 'config' && args[1] === 'trust-pin') {
    const cfg = cliConfig.loadConfig(DATA_DIR);
    const pending = cfg.pendingTlsPin;
    if (!pending?.host || !pending?.pin) {
      console.log('[-] 无待信任的 pin，请先执行 config test');
      process.exit(1);
    }
    identityStore.trustTlsPin(DATA_DIR, pending.host, pending.pin);
    cliConfig.clearPendingTlsPin(DATA_DIR);
    console.log(`[+] 已信任 ${pending.host} 的 TLS pin`);
    return;
  }

  if (args[0] === 'group' && (args[1] === 'rm' || args[1] === 'delete') && args[2]) {
    try {
      const g = findGroup(args[2]);
      deleteGroup(args[2]);
      console.log(`[+] 已删除群聊: ${g.name}`);
    } catch (e) {
      console.log('[-]', e.message);
      process.exit(1);
    }
    return;
  }

  if (args[0] === 'watch') {
    if (args[1] === 'stop') {
      if (watchSessionStore.stopWatchDaemon(DATA_DIR)) {
        console.log('[+] 已停止后台监听');
      } else {
        console.log('[-] 无运行中的后台监听');
      }
      return;
    }
    if (args[1] === 'status') {
      const sess = watchSessionStore.loadWatchSession(DATA_DIR);
      if (!sess) {
        console.log('[-] 无 watch-session（聊天内 /detach 会创建）');
        return;
      }
      console.log(`[*] 会话: ${sess.title}`);
      console.log(`[*] 房间: ${sess.roomId}`);
      if (watchSessionStore.isWatchRunning(DATA_DIR)) {
        console.log(`[*] 运行中 pid ${watchSessionStore.getWatchPid(DATA_DIR)}`);
        console.log('[*] 日志:', watchSessionStore.watchLogPath(DATA_DIR));
      } else {
        console.log('[*] 未运行 — 执行: node cli.js watch');
      }
      return;
    }
    await runWatchLoop();
    return;
  }

  if (args[0] === 'qr') {
    const payload = encodeContactPayload(identity.publicKey, null);
    const pngIdx = args.indexOf('--png');
    const pngPath = pngIdx >= 0 ? args[pngIdx + 1] : null;
    if (pngPath) {
      try {
        const out = await writeQrPng(payload, pngPath);
        console.log(`[+] 二维码 PNG: ${out}`);
        console.log('安全码:', fingerprint(identity.publicKey));
      } catch (e) {
        console.log('[-]', e.message);
        process.exit(1);
      }
      return;
    }
    console.log('\n我的二维码（让对方用 App 扫码或 CLI 粘贴）:\n');
    qrcodeTerminal.generate(payload, { small: true }, (code) => {
      console.log(code);
      console.log('\n安全码:', fingerprint(identity.publicKey));
      console.log('\n链接（可手动复制）:');
      console.log(payload);
      console.log('\nPNG: node cli.js qr --png ~/zerorelay-qr.png');
      console.log('');
    });
    return;
  }

  if (args[0] === 'add') {
    if (args[1] === '--' && args[2]) {
      await addContact(args.slice(2).join(' '));
      return;
    }
    if (args[1]) {
      await addContact(args.slice(1).join(' '));
      return;
    }
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const raw = await new Promise((r) => {
      rl.question('粘贴二维码内容或公钥后回车: ', (answer) => {
        rl.close();
        r(answer);
      });
    });
    if (!raw.trim()) {
      console.log('[-] 内容为空');
      process.exit(1);
    }
    await addContact(raw);
    return;
  }

  if (args[0] === 'chat' && args[1]) {
    const contact = findContact(args[1]);
    if (!contact) {
      console.log(`[-] ${ERR.CONTACT_NOT_FOUND}`);
      process.exit(1);
    }
    await chatWithContact(contact);
    return;
  }

  if (args[0] === 'group' && args[1] === 'create') {
    let name = args.slice(2).join(' ').trim();
    if (!name) {
      name = (await promptLine('群名称: ')).trim();
    }
    if (!name) {
      console.log(`[-] ${ERR.GROUP_NAME_REQUIRED}`);
      process.exit(1);
    }
    const members = await pickMembersFromContacts();
    const group = createGroup(name, members);
    console.log(`[+] 已创建群聊: ${group.name} (id=${group.id})`);
    if (members.length > 0) {
      const contacts = loadContacts();
      const names = members
        .map((id) => contacts.find((c) => c.id === id)?.name || id)
        .join('、');
      console.log(`[*] 已选成员: ${names}`);
    }
    await printGroupInvite(group);
    await offerEnterGroupChat(group);
    return;
  }

  if (args[0] === 'group' && args[1] === 'invite' && args[2]) {
    const group = findGroup(args[2]);
    if (!group) {
      console.log('[-] 未找到群聊');
      process.exit(1);
    }
    await printGroupInvite(group);
    return;
  }

  if (args[0] === 'group' && args[1] === 'join') {
    const raw =
      args[2] ||
      (await promptLine('粘贴群邀请链接后回车: '));
    if (!raw?.trim()) {
      console.log('[-] 内容为空');
      process.exit(1);
    }
    const payload = parseGroupInvite(raw);
    if (!payload) {
      console.log('[-] 群邀请无效');
      process.exit(1);
    }
    const group = joinGroupFromInvite(payload);
    console.log(`[+] 已加入群聊: ${group.name} (id=${group.id})`);
    await offerEnterGroupChat(group);
    return;
  }

  if (args[0] === 'group' && args[1] === 'chat' && args[2]) {
    const group = findGroup(args[2]);
    if (!group) {
      console.log('[-] 未找到群聊');
      process.exit(1);
    }
    await chatWithGroup(group);
    return;
  }

  if (args[0] === 'ratchet' && args[1] === 'export') {
    const rawPass = process.env.ZERO_RELAY_PASSPHRASE || (await promptLine('备份口令（至少8位）: '));
    let pass;
    try {
      pass = requirePassphrase(rawPass);
    } catch (e) {
      console.log(`[-] ${e.message}`);
      process.exit(1);
    }
    const blob = ratchetBackup.exportToBlob(pass, loadAllRatchets());
    if (args[2] === '--clipboard') {
      const { execSync } = require('child_process');
      try {
        execSync('pbcopy', { input: blob });
        console.log('[+] 棘轮备份已复制到剪贴板（与 Android 导出格式兼容）');
      } catch {
        console.log('[-] 剪贴板不可用，请省略 --clipboard 使用文件导出');
        process.exit(1);
      }
    } else {
      const out = ratchetBackup.exportToFile(DATA_DIR, pass, loadAllRatchets());
      console.log(`[+] 棘轮备份已写入 ${out}`);
    }
    return;
  }

  if (args[0] === 'ratchet' && args[1] === 'import') {
    const rawPass = process.env.ZERO_RELAY_PASSPHRASE || (await promptLine('备份口令: '));
    let pass;
    try {
      pass = requirePassphrase(rawPass);
    } catch (e) {
      console.log(`[-] ${e.message}`);
      process.exit(1);
    }
    let data;
    if (args[2] === '--clipboard') {
      const { execSync } = require('child_process');
      try {
        const blob = execSync('pbpaste', { encoding: 'utf8' }).trim();
        data = ratchetBackup.decryptRatchetBackup(pass, blob);
      } catch (e) {
        console.log(`[-] ${ERR.RATCHET_RESTORE_FAILED(e.message || e)}`);
        process.exit(1);
      }
    } else {
      try {
        data = ratchetBackup.importFromFile(DATA_DIR, pass);
      } catch (e) {
        console.log(`[-] ${ERR.RATCHET_RESTORE_FAILED(e.message || e)}`);
        process.exit(1);
      }
    }
    saveAllRatchets(data);
    console.log('[+] 棘轮状态已恢复');
    return;
  }

  if (args[0] === 'group' && args[1] === 'rotate' && args[2]) {
    try {
      const group = rotateGroupKey(args[2]);
      console.log(`[+] 群密钥已轮换为 v${group.keyVersion}，邀请已续期 7 天`);
      await printGroupInvite(group);
    } catch (e) {
      console.log('[-]', e.message);
      process.exit(1);
    }
    return;
  }

  if (args[0] === 'group' && args[1] === 'list') {
    const groups = loadGroups();
    const contacts = loadContacts();
    if (groups.length === 0) {
      console.log('暂无群聊。创建: node cli.js group create <名称>');
      return;
    }
    groups.forEach((g, i) => {
      console.log(`\n  [g${i + 1}]`);
      printGroupSummary(g, contacts);
    });
    console.log('\n进入群聊: node cli.js group chat <群id>  或直接运行 node cli.js 选 g+编号\n');
    return;
  }

  if (args.length > 0) {
    console.log('[-] 未知命令，执行: node cli.js help');
    process.exit(1);
  }

  await runInteractiveMainMenu(identity);
}

if (require.main === module) {
  main().catch(console.error);
} else {
  module.exports = { runWatchLoop };
}
