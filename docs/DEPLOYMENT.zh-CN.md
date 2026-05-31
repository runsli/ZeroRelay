# 部署指南

**语言：** [English](DEPLOYMENT.md) | 简体中文

生产环境中继：**Cloudflare Workers + KV**。客户端使用 **HTTPS** 与 **WSS**（`POST /t` + `/ws`）。协议与安全见 [PROTOCOL.zh-CN.md](PROTOCOL.zh-CN.md)、[SECURITY.zh-CN.md](SECURITY.zh-CN.md)。

```
┌─────────────┐     HTTPS POST /t      ┌──────────────────────────┐
│ Android /   │ ──────────────────────►│ Cloudflare Workers       │
│ CLI         │     WSS /ws            │ + KV（密文缓存）          │
└─────────────┘                        └──────────────────────────┘
       │                                         │
       └─ 明文仅留在设备端 ───────────────────────┘ 中继：routeHash + 密文
```

| 阶段 | 目标 | 命令 |
|------|------|------|
| 本地 | 开发 / 互操作测试 | `cd server && npm start` |
| 预发 | Workers 运行时 | `cd server && npm run dev` |
| 生产 | 公网中继 | `cd server && npm run deploy` |

---

## 一键部署

使用 [README.zh-CN.md](../README.zh-CN.md) 中的 Deploy 按钮（指向 `server/`）。部署后：

- 健康检查：`curl https://<worker>.workers.dev/health`
- Android / CLI：在设置中填写 `https://<worker>.workers.dev`

Fork 了仓库？运行 `./scripts/deploy-button-url.sh` 生成你的按钮链接。

---

## 本地中继

```bash
cd server && npm install && npm start
# http://localhost:8787
```

| 变量 | 说明 |
|------|------|
| `PORT` | 监听端口（默认 `8787`） |
| `MESSAGE_TTL_SEC` / `MAX_MESSAGES_PER_ROOM` | 与 Workers 相同 |
| `ENABLE_LEGACY_HTTP` | `1` 启用本地旧版 JSON 接口 |
| `TRANSPORT_LOG` / `RELAY_LOG` | `1` 输出日志 |

验证：`curl -s http://127.0.0.1:8787/` 与仓库根目录 `npm run test:interop`。

| 客户端 | 地址 |
|--------|------|
| 模拟器 | `http://10.0.2.2:8787` |
| 局域网真机 | `http://<电脑局域网IP>:8787` |

---

## 手动部署 Cloudflare Workers

**前置：** Cloudflare 账号、Node.js **≥ 22**、`cd server && npm install`

```bash
cd server && npx wrangler login
npx wrangler kv namespace create MESSAGE_KV
# 将 namespace id 写入 wrangler.toml — 见 wrangler.toml.example
npm run check && npm run deploy
```

自定义域名：Workers 控制台 → **Domains & Routes**。客户端使用 `https://你的域名`（含 WSS）。

### Worker `[vars]`

| 变量 | 默认 | 说明 |
|------|------|------|
| `MESSAGE_TTL_SEC` | `7200` | KV 存活时间（秒） |
| `MAX_MESSAGES_PER_ROOM` | `100` | 每房间最大消息数 |
| `ENABLE_LEGACY_HTTP` | `0` | `1` = 旧版 `/send`、`/messages` |
| `RELAY_LOG` | `0` | `1` = 错误日志 |

密钥：`wrangler secret put <NAME>`。勿提交 `.dev.vars` 或 `server/.wrangler/`。

---

## 连接客户端

### Android（Release）

1. 仅 HTTPS 中继（禁止明文）。
2. 设置 → 服务器地址 → **测试连接** → 按提示信任 TLS pin。
3. 聊天前核对安全码。

### CLI

```bash
zerorelay config set server https://relay.example.com
zerorelay config test
zerorelay config trust-pin   # 若需要
```

---

## 上线前检查清单

- [ ] `ENABLE_LEGACY_HTTP=0`；客户端仅 `POST /t` + `wss`
- [ ] Git 中无签名密钥；生产 `RELAY_LOG=0`
- [ ] HTTPS 域名有效
- [ ] `npm run audit:all` 无 high/critical
- [ ] 对生产中继运行 `npm run test:interop`
- [ ] 告知用户：中继可见元数据（时间、大小、`routeHash`）；消息正文为 E2EE

KV 默认保留：2 小时 / 每房间 100 条消息。
