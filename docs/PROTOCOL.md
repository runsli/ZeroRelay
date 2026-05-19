# ZeroRelay protocol alignment

**Languages:** English | [简体中文](PROTOCOL.zh-CN.md)

CLI (Node), Android (Kotlin), and Server (TypeScript) must keep the constants and behaviors below in sync. When changing any side, update this document and `scripts/interop-test.js`.

## Source map

| Area | CLI | Android | Server |
|------|-----|---------|--------|
| Protocol / polling | `cli-protocol.js` | `data/crypto/ProtocolPolicy.kt` | `src/relay-security.ts` (partial) |
| Relay signing / room token | `cli-relay-crypto.js` | `data/crypto/RelayCrypto.kt` | `src/relay-security.ts` |
| Message ratchet | `cli.js` (inline) | `data/crypto/MessageRatchet.kt` | — |
| WebSocket client | `cli-ws.js` | `data/chat/ChatRepository.kt` | `src/server.ts` / `server-local.js` |
| Inbound handling | `cli-inbound.js` | `ChatRepository.processIncoming` | — |

## Cryptographic constants

| Name | Value |
|------|-------|
| Direct-chat HKDF info | `zero-relay-v1` |
| Group room salt | `zero-relay-group-v1` |
| Room access token | HMAC-SHA256, `info = zero-relay-room-access-v1` |
| Signing HKDF salt | `zero-relay-sign-v1` |
| Signing seed info | `ed25519-seed` |
| Protocol version v2 | `2` |
| Ratchet send chain (lexicographically lower party) | `chain-send-v2` / peer `chain-recv-v2` |
| Legacy static-key cutoff | `2026-01-01T00:00:00Z` (`1767225600000` ms) |

## Message envelope (v2)

**Direct-chat plaintext (above ratchet):**

```json
{ "v": 2, "s": <send_seq>, "t": "<padded_plaintext_b64>" }
```

**Group chat:**

```json
{ "v": 2, "kv": <key_version>, "t": "<padded_plaintext_b64>" }
```

**Padding:** 256-byte blocks; byte `0x01` + 4-byte big-endian length + UTF-8 body + random fill.

## Obfuscated routing and full tunnel (recommended)

| Name | Value |
|------|-------|
| Route bucket `routeId` | `base64url(SHA-256(roomToken raw 32 bytes))` |
| Tunnel HKDF info | `zero-relay-tunnel-v1` |
| Tunnel frame version | `0x02` |
| Tunnel AES key | `HKDF-SHA256(routeHash, info=zero-relay-tunnel-v1)` |

**`POST /t`** (`Content-Type: application/octet-stream`)  
Frame: `version(1) | routeHash(32) | iv(12) | AES-GCM(inner JSON)` — **no plaintext `roomToken`**

Inner JSON:

- Send: `{ "op":"send", "ciphertext", "iv", "tag", "senderPk", "signPk", "sig", "timestamp" }`
- Poll: `{ "op":"poll", "since", "timeout" }`

Ed25519 signature payload uses **`routeId`** (not E2EE `roomId`). Relay storage and gates only see `routeId` + ciphertext.

## HTTP API

**Recommended:** `POST /t` only (full tunnel; inner JSON may include `_p` padding buckets 256/512/1024/2048).

**Legacy JSON** (off by default): set `ENABLE_LEGACY_HTTP=1` for `/send`, `/messages`; auth header `X-Route-Hash`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Health check |
| POST | `/t` | **Recommended** send/poll |
| POST | `/send` | Legacy JSON send (410 by default; `Deprecation`/`Sunset` when enabled) |
| POST | `/messages` | Legacy poll (410 by default) |
| GET | `/messages` | **Removed** (410) |

## WebSocket

**URL:** `{ws|wss}://host/ws` (no query params — avoids `roomId`/`senderId` in URL logs)

**First frame (client → server):**

```json
{ "type": "auth", "routeHash": "<base64 SHA-256(roomToken)>", "senderId": "<id>" }
```

(`routeHash` = standard base64 of 32-byte SHA-256(roomToken raw bytes); server derives `routeId`; **do not** send `roomToken` or E2EE `roomId`.)

**Server → client:**

| type | Description |
|------|-------------|
| `auth_ok` | Auth succeeded |
| `joined` | Joined room |
| `auth_required` | Resend auth |
| `auth_error` | Auth failed |
| `message` | `{ message: EncryptedMessage }` |
| `history` | `{ messages: [...] }` on join |

**Transport:**

- **Android / CLI:** WebSocket receive + HTTP long-poll fallback
- **CLI poll interval:** ~`POLL_WS_CONNECTED_MS` (30s + jitter) when WS connected; else exponential backoff 1s–15s

## Interop tests

```bash
# Terminal 1
cd server && npm start

# Terminal 2 (repo root)
npm install
npm run test:interop
```

Covers: direct HTTP poll, direct WebSocket delivery, group HTTP poll, ratchet backup format (Android-compatible).

## CLI ↔ Android (P1)

| Feature | CLI command |
|---------|-------------|
| Contacts / safety number | `contact list` |
| Verify safety number | `contact verify <id>` |
| Remove contact / group | `contact rm <id>` / `group rm <id>` |
| Default server | `config set server <url>`, `config test` |
| TLS pin change | `config test` → `config trust-pin` |
| Ratchet backup | `ratchet export` / `import` (`--clipboard` optional) |

Contact JSON fields match Android: `pk`, `vf`, `vfa`. Unverified contacts cannot send in direct chat (same as App).

Ratchet backup: PBKDF2-HMAC-SHA256 **120000** iterations; `data` = AES-GCM ciphertext + 16-byte tag.

## CLI ↔ Android (P2)

| Item | Notes |
|------|--------|
| Group JSON | `room`, `kv`, `exp`, `m`, `pk` (IdentityStore) |
| Contact JSON | `pk`, `vf`, `vfa` |
| Passphrase | Backup ≥8 chars (`cli-passphrase.js`) |
| Recents | `recentContactIds` / `recentRoomIds` in `config.json` (max 10) |
| Send validation | Unverified block, expired group, message too long (`protocol.padPlaintext`) |
| Error strings | `cli-errors.js` ↔ `strings.xml` |
| Debug logs | `CRYPTO_LOG=1` ↔ Logcat `ZeroRelay.Crypto`; `CRYPTO_LOG_PLAINTEXT=1` for plaintext preview |

Example `~/.zero-relay/config.json`:

```json
{
  "serverUrl": "http://192.168.1.10:8787",
  "recentContactIds": ["abc123…"],
  "recentRoomIds": ["roomId…"]
}
```

## CLI enhancements (P3)

| Item | Command / action |
|------|------------------|
| Background receive | `/detach` in chat → `watch` daemon; `watch stop` |
| Terminal notify | Bell + OSC on inbound (terminal-dependent) |
| QR PNG | `node cli.js qr --png ~/qr.png` |
| Interactive menu | No args: `a` add, `q` QR, `c` config, `w` watch, `h` help |
| Single binary | `npm run build:cli` → `dist/zerorelay-*` (dev deps) |

`watch-session.json` holds room keys, mode `0600` — do not share.

## Invite formats

- Contact: `zerorelay://v1?d=<base64url(json)>`
- Group: `zerorelay://group?v=1&d=<base64url(json)>`
