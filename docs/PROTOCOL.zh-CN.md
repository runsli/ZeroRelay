# ZeroRelay 协议对齐清单

**语言：** [English](PROTOCOL.md) | 简体中文

CLI（Node）、Android（Kotlin）与 Server（TypeScript）须保持下列常量与行为一致。修改任一端时请同步更新本文档与 `scripts/interop-test.js`。

## 源文件对照

| 领域 | CLI | Android | Server |
|------|-----|---------|--------|
| 协议常量 / 轮询 | `cli-protocol.js` | `data/crypto/ProtocolPolicy.kt` | `src/relay-security.ts`（部分） |
| 中继签名 / 房间令牌 | `cli-relay-crypto.js` | `data/crypto/RelayCrypto.kt` | `src/relay-security.ts` |
| 消息棘轮 | `cli.js`（内联） | `data/crypto/MessageRatchet.kt` | — |
| WebSocket 客户端 | `cli-ws.js` | `data/chat/ChatRepository.kt` | `src/server.ts` / `server-local.js` |
| 入站处理 | `cli-inbound.js` | `ChatRepository.processIncoming` | — |

## 密码学常量

| 名称 | 值 |
|------|-----|
| 单聊 HKDF info | `zero-relay-v1` |
| 群房间 salt | `zero-relay-group-v1` |
| 房间访问令牌 | HMAC-SHA256，`info = zero-relay-room-access-v1` |
| 签名 HKDF salt | `zero-relay-sign-v1` |
| 签名 seed info | `ed25519-seed` |
| 协议版本 v2 | `2` |
| 棘轮链 send（字典序低方） | `chain-send-v2` / 对方 `chain-recv-v2` |
| 旧版静态密钥截止 | `2026-01-01T00:00:00Z`（`1767225600000` ms） |

## 消息信封（v2）

**单聊明文（棘轮层之上）：**

```json
{ "v": 2, "s": <send_seq>, "t": "<padded_plaintext_b64>" }
```

**群聊：**

```json
{ "v": 2, "kv": <key_version>, "t": "<padded_plaintext_b64>" }
```

**填充：** 256 字节块；首字节 `0x01` + 4 字节大端长度 + UTF-8 正文 + 随机填充。

## 混淆路由与全隧道（推荐）

| 名称 | 值 |
|------|-----|
| 路由桶 `routeId` | `base64url(SHA-256(roomToken 原始 32 字节))` |
| 隧道 HKDF info | `zero-relay-tunnel-v1` |
| 隧道帧版本 | `0x02` |
| 隧道 AES 密钥 | `HKDF-SHA256(routeHash, info=zero-relay-tunnel-v1)` |

**`POST /t`**（`Content-Type: application/octet-stream`）  
帧布局：`version(1) | routeHash(32) | iv(12) | AES-GCM(JSON 内层)` — **不含 roomToken 明文**  

内层 JSON：

- 发送：`{ "op":"send", "ciphertext", "iv", "tag", "senderPk", "signPk", "sig", "timestamp" }`
- 轮询：`{ "op":"poll", "since", "timeout" }`

Ed25519 签名载荷使用 **`routeId`**（不再使用 E2EE `roomId`）。中继存储与 gate 仅见 `routeId` + 密文，不见聊天 `roomId`。

## HTTP API

**推荐：** 仅 `POST /t`（全隧道，内层 JSON 可含 `_p` 填充档 256/512/1024/2048）。

**Legacy JSON**（默认关闭）：`ENABLE_LEGACY_HTTP=1` 时启用 `/send`、`/messages`；鉴权头 `X-Route-Hash`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 健康检查 |
| POST | `/t` | **推荐** 全隧道收发 |
| POST | `/send` | Legacy 明文 JSON（默认 410；开启时 `Deprecation`/`Sunset`） |
| POST | `/messages` | Legacy 轮询（默认 410） |
| GET | `/messages` | **已移除**（410） |

## WebSocket

**URL：** `{ws|wss}://host/ws`（无查询参数；避免 `roomId`/`senderId` 进入 URL 日志）

**连接后首帧（客户端 → 服务端）：**

```json
{ "type": "auth", "routeHash": "<base64 SHA-256(roomToken)>", "senderId": "<id>" }
```

（`routeHash` = 标准 base64 的 32 字节 SHA-256(roomToken 原始字节)；`routeId` 由服务端推导，**不上传** `roomToken` 或 E2EE `roomId`。）

**服务端 → 客户端：**

| type | 说明 |
|------|------|
| `auth_ok` | 鉴权成功 |
| `joined` | 已加入房间 |
| `auth_required` | 需重发 auth |
| `auth_error` | 鉴权失败 |
| `message` | `{ message: EncryptedMessage }` |
| `history` | `{ messages: [...] }` 进房历史 |

**传输策略：**

- **Android / CLI：** WebSocket 收消息 + HTTP 长轮询备用
- **CLI 轮询间隔：** WS 已连接时约 `POLL_WS_CONNECTED_MS`（30s + 抖动）；否则指数退避 1s–15s

## 互操作测试

```bash
# 终端 1
cd server && npm start

# 终端 2（项目根目录）
npm install
npm run test:interop
```

覆盖：单聊 HTTP 轮询、单聊 WebSocket 投递、群聊 HTTP 轮询、棘轮备份格式（与 Android 一致）。

## CLI 与 Android 对齐（P1）

| 能力 | CLI 命令 |
|------|----------|
| 联系人列表 / 安全码 | `contact list` |
| 核对安全码 | `contact verify <id>` |
| 删除联系人 / 群 | `contact rm <id>` / `group rm <id>` |
| 默认服务器 | `config set server <url>`、`config test` |
| TLS pin 变更 | `config test` 提示 → `config trust-pin` |
| 棘轮备份 | `ratchet export` / `import`（`--clipboard` 可选） |

联系人 JSON 字段与 Android 一致：`pk`、`vf`、`vfa`。未验证联系人单聊内不可发送（与 App 一致）。

棘轮备份：PBKDF2-HMAC-SHA256 **120000** 次，`data` = AES-GCM 密文 + 16 字节 tag。

## CLI 与 Android 对齐（P2）

| 项 | 说明 |
|----|------|
| 群聊 JSON | `room`、`kv`、`exp`、`m`、`pk`（与 IdentityStore 一致） |
| 联系人 JSON | `pk`、`vf`、`vfa` |
| 口令 | 备份 ≥8 位（`cli-passphrase.js`） |
| 最近使用 | `config.json` 内 `recentContactIds` / `recentRoomIds`（最多 10） |
| 发送校验 | 未验证不可发、群过期、消息过长（`protocol.padPlaintext`） |
| 错误文案 | `cli-errors.js` ↔ `strings.xml` — 对照表：[error-manifest.json](error-manifest.json)；CI：`node scripts/check-error-parity.js` |
| 调试日志 | `CRYPTO_LOG=1` ↔ Logcat `ZeroRelay.Crypto`；`CRYPTO_LOG_PLAINTEXT=1` 可看明文预览 |

`~/.zero-relay/config.json` 示例：

```json
{
  "serverUrl": "http://192.168.1.10:8787",
  "recentContactIds": ["abc123…"],
  "recentRoomIds": ["roomId…"]
}
```

## CLI 体验增强（P3）

| 项 | 命令 / 操作 |
|----|-------------|
| 后台收消息 | 聊天内 `/detach` → 自动 `watch` 守护进程；`watch stop` 停止 |
| 终端通知 | 后台收到他人消息时响铃 + OSC（视终端支持） |
| 二维码 PNG | `node cli.js qr --png ~/qr.png` |
| 交互主菜单 | 无参数启动：`a`添加 `q`二维码 `c`配置 `w`后台 `h`帮助 |
| 单文件分发 | `npm run build:cli` → `dist/zerorelay-*`（需 `npm install` 含 dev） |

`watch-session.json` 含房间密钥，权限 `0600`，勿分享。

## 邀请格式

- 联系人：`zerorelay://v1?d=<base64url(json)>`
- 群：`zerorelay://group?v=1&d=<base64url(json)>`
