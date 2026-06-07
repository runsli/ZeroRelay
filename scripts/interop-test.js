#!/usr/bin/env node
/**
 * P0 互操作测试：双 CLI 身份单聊 + 群聊 + WebSocket 投递
 * 需本地中继：ZERO_RELAY_SERVER=http://127.0.0.1:8787 npm run test:interop
 */
const crypto = require('crypto');
const http = require('http');
const https = require('https');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { x25519 } = require('@noble/curves/ed25519.js');

const protocol = require('../cli-protocol');
const relayCrypto = require('../cli-relay-crypto');
const { RelayWebSocket } = require('../cli-ws');
const { createInboundProcessor } = require('../cli-inbound');
const relayTunnel = require('../cli-relay-tunnel');

const SERVER = process.env.ZERO_RELAY_SERVER || 'http://127.0.0.1:8787';
const HKDF_INFO = 'zero-relay-v1';
const GROUP_ROOM_SALT = 'zero-relay-group-v1';
const PROTOCOL_V2 = protocol.PROTOCOL_V2;

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

function ok(msg) {
  console.log(`ok: ${msg}`);
}

function httpJson(urlPath, method, body, routeHashB64) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlPath, SERVER);
    const lib = u.protocol === 'https:' ? https : http;
    const payload = body ? JSON.stringify(body) : null;
    const req = lib.request(
      {
        hostname: u.hostname,
        port: u.port || (u.protocol === 'https:' ? 443 : 80),
        path: u.pathname + u.search,
        method,
        headers: {
          'Content-Type': 'application/json',
          ...(routeHashB64 ? { 'X-Route-Hash': routeHashB64 } : {}),
        },
      },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(data));
          } catch {
            reject(new Error(`invalid json: ${data.slice(0, 120)}`));
          }
        });
      },
    );
    req.on('error', reject);
    req.setTimeout(15000, () => {
      req.destroy();
      reject(new Error('timeout'));
    });
    if (payload) req.write(payload);
    req.end();
  });
}

function httpTunnel(frame) {
  return new Promise((resolve, reject) => {
    const u = new URL('/t', SERVER);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.request(
      {
        hostname: u.hostname,
        port: u.port || (u.protocol === 'https:' ? 443 : 80),
        path: u.pathname,
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream' },
      },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(data));
          } catch {
            reject(new Error(`invalid json: ${data.slice(0, 120)}`));
          }
        });
      },
    );
    req.on('error', reject);
    req.setTimeout(15000, () => {
      req.destroy();
      reject(new Error('timeout'));
    });
    req.write(frame);
    req.end();
  });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function waitUntil(fn, timeoutMs, intervalMs = 100) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (fn()) return resolve();
      if (Date.now() - start > timeoutMs) return reject(new Error('waitUntil timeout'));
      setTimeout(tick, intervalMs);
    };
    tick();
  });
}

function createIdentity() {
  const privateKey = x25519.utils.randomSecretKey();
  const publicKey = x25519.getPublicKey(privateKey);
  return {
    publicKey: Buffer.from(publicKey).toString('base64'),
    privateKey: Buffer.from(privateKey).toString('base64'),
  };
}

function senderIdFromPublicKey(pubB64) {
  return relayCrypto.senderIdFromPublicKey(pubB64);
}

function deriveRoomId(pubA, pubB) {
  const a = Buffer.from(pubA, 'base64');
  const b = Buffer.from(pubB, 'base64');
  const sorted = [a, b].sort((x, y) => Buffer.compare(x, y));
  return crypto.createHash('sha256').update(Buffer.concat(sorted)).digest('base64url');
}

function deriveSessionKey(privateKeyB64, peerPublicKeyB64, roomId) {
  const shared = x25519.getSharedSecret(
    Buffer.from(privateKeyB64, 'base64'),
    Buffer.from(peerPublicKeyB64, 'base64'),
  );
  return crypto.hkdfSync('sha256', Buffer.from(shared), roomId, HKDF_INFO, 32);
}

function hkdfExpand(ikm, salt, info, len = 32) {
  return crypto.hkdfSync('sha256', ikm, salt, Buffer.from(info, 'utf8'), len);
}

function encrypt(plaintext, key) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return {
    ciphertext: encrypted.toString('base64'),
    iv: iv.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
  };
}

function decrypt(ciphertext, iv, tag, key) {
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64'));
  decipher.setAuthTag(Buffer.from(tag, 'base64'));
  return Buffer.concat([
    decipher.update(Buffer.from(ciphertext, 'base64')),
    decipher.final(),
  ]).toString('utf8');
}

function createDirectSession(identity, peer, ratchetStore) {
  const roomId = deriveRoomId(identity.publicKey, peer.publicKey);
  const sessionKey = deriveSessionKey(identity.privateKey, peer.publicKey, roomId);
  const roomAccessToken = relayCrypto.deriveRoomAccessToken(sessionKey, roomId);
  const signingKeys = relayCrypto.deriveSigningKeys(identity.privateKey);
  const senderId = senderIdFromPublicKey(identity.publicKey);

  function ratchetFromRoot() {
    const salt = Buffer.from(roomId, 'utf8');
    const lower =
      Buffer.compare(Buffer.from(identity.publicKey, 'base64'), Buffer.from(peer.publicKey, 'base64')) <= 0;
    const sendInfo = lower ? 'chain-send-v2' : 'chain-recv-v2';
    const recvInfo = lower ? 'chain-recv-v2' : 'chain-send-v2';
    return {
      send: hkdfExpand(sessionKey, salt, sendInfo).toString('base64'),
      recv: hkdfExpand(sessionKey, salt, recvInfo).toString('base64'),
      ss: 0,
      rs: 0,
    };
  }

  function ratchetStep(chainB64) {
    const chainKey = Buffer.from(chainB64, 'base64');
    return {
      messageKey: hkdfExpand(chainKey, chainKey, 'message'),
      nextChain: hkdfExpand(chainKey, chainKey, 'chain').toString('base64'),
    };
  }

  function ratchetEncrypt(plaintext) {
    let state = ratchetStore.get(roomId) || ratchetFromRoot();
    const { messageKey, nextChain } = ratchetStep(state.send);
    state.send = nextChain;
    const seq = state.ss++;
    const envelope = JSON.stringify({ v: PROTOCOL_V2, s: seq, t: protocol.padPlaintext(plaintext) });
    const payload = encrypt(envelope, messageKey);
    ratchetStore.set(roomId, state);
    return payload;
  }

  function ratchetDecrypt(msg) {
    let state = ratchetStore.get(roomId) || ratchetFromRoot();
    const { messageKey, nextChain } = ratchetStep(state.recv);
    let plain;
    try {
      plain = decrypt(msg.ciphertext, msg.iv, msg.tag, messageKey);
    } catch {
      return null;
    }
    const env = JSON.parse(plain);
    if (env.v === PROTOCOL_V2 && env.s === state.rs) {
      state.recv = nextChain;
      state.rs += 1;
      ratchetStore.set(roomId, state);
      return protocol.unwrapPlaintext(env.t);
    }
    return null;
  }

  async function send(plaintext) {
    const payload = ratchetEncrypt(plaintext);
    const now = Date.now();
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
    const res = await httpTunnel(frame);
    if (!res.success) throw new Error(`send failed: ${JSON.stringify(res)}`);
    return inner;
  }

  return {
    roomId,
    sessionKey,
    roomAccessToken,
    senderId,
    signingKeys,
    identity,
    peer,
    ratchetDecrypt,
    send,
  };
}

function deriveGroupRoomId(groupId) {
  return crypto.createHmac('sha256', Buffer.from(GROUP_ROOM_SALT)).update(groupId).digest('base64url');
}

function createGroupSession(identity, groupKeyB64, groupId) {
  const groupKey = Buffer.from(groupKeyB64, 'base64');
  const roomId = deriveGroupRoomId(groupId);
  const roomAccessToken = relayCrypto.deriveRoomAccessToken(groupKey, roomId);
  const signingKeys = relayCrypto.deriveSigningKeys(identity.privateKey);
  const senderId = senderIdFromPublicKey(identity.publicKey);

  async function send(plaintext) {
    const envelope = JSON.stringify({
      v: PROTOCOL_V2,
      kv: 1,
      t: protocol.padPlaintext(plaintext),
    });
    const payload = encrypt(envelope, groupKey);
    const now = Date.now();
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
    const res = await httpTunnel(frame);
    if (!res.success) throw new Error(`group send failed: ${JSON.stringify(res)}`);
    return inner;
  }

  return { roomId, roomAccessToken, senderId, send };
}

async function assertServer() {
  const res = await httpJson('/', 'GET');
  if (!res || res.status !== 'ok') fail(`server not healthy at ${SERVER}`);
  ok(`server ${SERVER}`);
}

async function testDirectPoll() {
  const a = createIdentity();
  const b = createIdentity();
  const storeA = new Map();
  const storeB = new Map();
  const clientA = createDirectSession(a, b, storeA);
  const clientB = createDirectSession(b, a, storeB);

  await clientA.send('hello-from-a');
  await sleep(300);
  const { frame: pollFrame } = relayTunnel.encodeTunnelFrame(clientB.roomAccessToken, {
    op: 'poll',
    since: 0,
    timeout: 2000,
  });
  const poll = await httpTunnel(pollFrame);
  if (!poll.success || !poll.messages?.length) fail('direct poll: no messages');
  const text = clientB.ratchetDecrypt(poll.messages[poll.messages.length - 1]);
  if (text !== 'hello-from-a') fail(`direct poll: expected hello-from-a got ${text}`);
  ok('direct chat HTTP poll');
}

async function testDirectWebSocket() {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'zr-interop-ws-'));
  const a = createIdentity();
  const b = createIdentity();
  const storeB = new Map();
  const clientB = createDirectSession(b, a, storeB);

  const received = [];
  const ws = new RelayWebSocket({
    serverUrl: SERVER,
    dataDir,
    roomId: clientB.roomId,
    senderId: clientB.senderId,
    roomAccessToken: clientB.roomAccessToken,
    onMessage: (m) => received.push(m),
  });
  ws.connect();
  await waitUntil(() => ws.isConnected(), 8000);

  const clientA = createDirectSession(a, b, new Map());
  await clientA.send('hello-ws');

  await waitUntil(() => received.length > 0, 8000);
  const text = clientB.ratchetDecrypt(received[received.length - 1]);
  if (text !== 'hello-ws') fail(`websocket: expected hello-ws got ${text}`);
  ws.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
  ok('direct chat WebSocket delivery');
}

async function testGroupPoll() {
  const groupKey = crypto.randomBytes(32).toString('base64');
  const groupId = crypto.randomBytes(8).toString('hex');
  const a = createIdentity();
  const b = createIdentity();
  const ga = createGroupSession(a, groupKey, groupId);
  const gb = createGroupSession(b, groupKey, groupId);

  await ga.send('group-hi');
  await sleep(300);
  const { frame: gPollFrame } = relayTunnel.encodeTunnelFrame(gb.roomAccessToken, {
    op: 'poll',
    since: 0,
    timeout: 2000,
  });
  const poll = await httpTunnel(gPollFrame);
  if (!poll.success || !poll.messages?.length) fail('group poll: no messages');

  const keys = new Map([[1, Buffer.from(groupKey, 'base64')]]);
  const seen = new Set();
  const ingest = createInboundProcessor({
    seenIds: seen,
    formatPrefix: () => '',
    isGroupChat: true,
    groupKeys: keys,
    roomId: gb.roomId,
    roomAccessToken: gb.roomAccessToken,
    sessionKey: Buffer.from(groupKey, 'base64'),
    localPublicKey: b.publicKey,
    peerPublicKey: null,
    protocol,
    legacyFlag: { value: false },
    deps: {
      ratchetDecrypt: () => null,
      decryptGroupMessage: (ct, iv, tag, kv, sid) => {
        try {
          const plain = decrypt(ct, iv, tag, kv.get(1));
          const env = JSON.parse(plain);
          if (env.v === PROTOCOL_V2) return protocol.unwrapPlaintext(env.t);
        } catch {
          return null;
        }
        return null;
      },
    },
  });
  const out = ingest(poll.messages[0]);
  if (!out?.line?.includes('group-hi')) fail(`group poll decrypt failed: ${out?.line}`);
  ok('group chat HTTP poll');
}

async function testGroupStorageSchema() {
  const fs = require('fs');
  const os = require('os');
  const path = require('path');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'zr-group-schema-'));
  const groupsFile = path.join(tmp, 'groups.json');
  const sample = [
    {
      id: 'gid1',
      name: '测试群',
      room: 'roomabc',
      key: Buffer.alloc(32).toString('base64'),
      at: Date.now(),
      kv: 2,
      exp: Date.now() + 86400000,
      m: ['c1'],
      pk: { 1: Buffer.alloc(32).toString('base64') },
    },
  ];
  fs.writeFileSync(groupsFile, JSON.stringify(sample));
  const loaded = JSON.parse(fs.readFileSync(groupsFile, 'utf8')).map((raw) => {
    const prevRaw = raw.pk || {};
    const previousKeys = {};
    for (const [k, v] of Object.entries(prevRaw)) previousKeys[parseInt(k, 10)] = v;
    return {
      id: raw.id,
      name: raw.name,
      roomId: raw.room,
      key: raw.key,
      members: raw.m || [],
      keyVersion: raw.kv,
      inviteExpiresAt: raw.exp,
      previousKeys,
    };
  });
  if (loaded[0].keyVersion !== 2 || !loaded[0].previousKeys[1]) {
    fail('group schema parse failed');
  }
  fs.rmSync(tmp, { recursive: true, force: true });
  ok('group storage schema (Android pk/kv/exp/m)');
}

async function testRatchetBackupFormat() {
  const ratchetBackup = require('../cli-ratchet-backup');
  const sample = { 'room-test': { send: 'abc', recv: 'def', ss: 1, rs: 0 } };
  const pass = 'test-passphrase-8';
  const blob = ratchetBackup.exportToBlob(pass, sample);
  const parsed = JSON.parse(blob);
  if (!parsed.data || parsed.ciphertext) fail('ratchet backup: expected Android data field');
  const restored = ratchetBackup.decryptRatchetBackup(pass, blob);
  if (restored['room-test'].ss !== 1) fail('ratchet backup roundtrip failed');
  ok('ratchet backup Android-compatible format');
}

async function main() {
  console.log('ZeroRelay interop tests\n');
  await assertServer();
  await testDirectPoll();
  await testDirectWebSocket();
  await testGroupPoll();
  await testGroupStorageSchema();
  await testRatchetBackupFormat();
  console.log('\nAll interop tests passed.');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
