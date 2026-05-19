# ZeroRelay Web 网页端

**语言：** [English](README.md) | 简体中文

浏览器客户端（MVP），与 [docs/PROTOCOL.zh-CN.md](../docs/PROTOCOL.zh-CN.md) 对齐 — 与 CLI、Android 共用 `POST /t` 隧道、WebSocket 鉴权、v2 棘轮与联系人二维码。

## 环境要求

- Node.js **18+**
- 已运行的中继（本地 `cd server && npm start`，或生产环境 Cloudflare Workers）

## 本地开发

```bash
cd web
npm install
npm run dev
```

打开 http://localhost:5173

1. 服务器地址填 `http://127.0.0.1:8787`（Vite 将 `/relay` 代理到本地中继）。
2. 分享你的二维码 / 链接给其他客户端（CLI、Android 或 Web）。
3. 添加对方联系人载荷，核对安全码后标记**已验证**，即可聊天。

## 构建

```bash
npm run build
# 静态文件在 web/dist/
```

生产环境请在 **HTTPS** 后提供 `dist/`。

---

## 部署到 Cloudflare

### 一键部署（推荐）：与中继同一按钮

Cloudflare [Deploy to Cloudflare](https://developers.cloudflare.com/workers/platform/deploy-buttons/) **不支持**仅 Pages 的一键部署。本仓库将网页打进 **Worker**（`npm run build:web` → `server/public`）。

使用根目录 [README.zh-CN.md](../README.zh-CN.md) 中指向 **`server/`** 的按钮。部署后：

1. 在浏览器打开 `https://<你的-worker>.workers.dev`。
2. 界面自动以该域名为中继（无需配置 `VITE_DEFAULT_RELAY_URL`）。

手动：`cd server && npm run deploy`（会先构建 `../web`）。

---

### 可选：仅 Cloudflare Pages

网页为**静态 SPA**，也可与 Worker **分开**部署（两个 URL）。

### 架构

| 组件 | Cloudflare 产品 | URL 示例 |
|------|-----------------|----------|
| 中继（API + WebSocket） | **Workers** | `https://zero-relay-server.<账号>.workers.dev` |
| 网页 UI | **Pages** | `https://zerorelay-web.pages.dev` |

浏览器须访问 **HTTPS** 中继。中继已允许浏览器跨域（`Access-Control-Allow-Origin: *`）。

### 方式 A — 控制台（推荐）

1. 先部署中继（[README.zh-CN.md](../README.zh-CN.md) 或 `server/` 的 Deploy 按钮）。
2. 复制 Worker URL，例如 `https://zero-relay-server.<你>.workers.dev`。
3. Cloudflare 控制台 → **Workers & Pages** → **创建** → **Pages** → **连接 Git**。
4. 构建配置：

   | 项 | 值 |
   |----|-----|
   | 根目录 | `web`（或见下方仓库根目录方式） |
   | 构建命令 | `npm ci && npm run build` |
   | 输出目录 | `dist` |
   | Node 版本 | 20 |

   若以仓库根为项目根：

   - 构建命令：`cd web && npm ci && npm run build`
   - 输出目录：`web/dist`

5. **设置 → 环境变量**（生产）：

   | 名称 | 值 |
   |------|-----|
   | `VITE_DEFAULT_RELAY_URL` | Worker 的 HTTPS 地址（无末尾 `/`） |

6. 重新部署。打开 Pages 地址应进入首页（中继已预设）；用户仍可在 **更改** 中修改中继。

### 方式 B — Wrangler CLI

```bash
cd web
npm ci
VITE_DEFAULT_RELAY_URL=https://zero-relay-server.<你>.workers.dev npm run build
npx wrangler pages project create zerorelay-web   # 仅首次
npx wrangler pages deploy dist --project-name zerorelay-web
```

Git 构建建议在 Pages 控制台设置 `VITE_DEFAULT_RELAY_URL`，避免每次本地部署都传参。

### 自定义域名（可选）

- **Pages**：如 `chat.example.com` → Pages 项目。
- **Worker**：如 `relay.example.com` → Worker 路由。
- 设置 `VITE_DEFAULT_RELAY_URL=https://relay.example.com` 并重新部署 Pages。

### 部署后可用功能

- 打开 Pages URL → 显示身份与二维码（若已设 `VITE_DEFAULT_RELAY_URL`）。
- 添加联系人、验证安全码，与 CLI / Android / 其他 Web 标签页 1:1 聊天。
- 密钥保存在本机浏览器 **IndexedDB**（不在 Cloudflare 上）。

### 限制

- Web 与 Worker 为**两次**部署（除非合并静态资源到 Worker — 本仓库默认已合并）。
- 无 CLI 式 TLS 证书固定；依赖浏览器 CA 信任。
- Web MVP 暂不支持群聊。

---

## 功能范围（MVP）

| 功能 | 状态 |
|------|------|
| 身份 + IndexedDB 存储 | 支持 |
| 联系人二维码 / 安全码 | 支持 |
| 1:1 聊天（棘轮 v2） | 支持 |
| WebSocket + HTTP 轮询回退 | 支持 |
| Cloudflare Pages + 预设中继 URL | 支持（`VITE_DEFAULT_RELAY_URL`） |
| 群聊 | 尚未支持 |
| 口令加密身份 | 尚未支持 |
| TLS pin（CLI 式 TOFU） | 仅浏览器系统信任 |

## 安全说明

私钥仅存在**本设备**的 IndexedDB。生产环境请使用 HTTPS 中继。网页端不实现 CLI 式证书固定，请依赖浏览器 CA 与中继域名。
