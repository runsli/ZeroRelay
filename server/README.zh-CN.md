# ZeroRelay 服务端（Worker + 中继 API + 网页）

**语言：** [English](README.md) | 简体中文

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/runsli/ZeroRelay/tree/main/server)

## 一键部署（推荐）

1. 点击上方 **Deploy to Cloudflare**（必须指向 **`server/`**，不是 `web/`）。
2. 登录 Cloudflare 并授权 GitHub。
3. 确认 fork / Worker 名称 → Cloudflare 创建 **KV** 并执行 `npm run build`（将 `../web` 构建到 `public/`）。
4. 打开 `https://<你的-worker>.workers.dev` — 浏览器与中继共用此地址。

健康检查：`curl https://<你的-worker>.workers.dev/health`

## 为什么不用 Cloudflare Pages 一键部署？

官方 [Deploy to Cloudflare](https://developers.cloudflare.com/workers/platform/deploy-buttons/) **不支持**纯 Pages 应用。本项目的网页作为 **Worker 静态资源**（`wrangler.toml` 中的 `[assets]`）。可选单独 Pages 部署见 [web/README.zh-CN.md](../web/README.zh-CN.md)。

## 手动部署（CLI）

```bash
cd server
npm install
npx wrangler kv namespace create MESSAGE_KV   # 首次；将 id 写入 wrangler.toml
npm run deploy
```

需要 Node.js **22+**（Wrangler 4）。
