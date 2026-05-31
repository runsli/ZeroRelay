# ZeroRelay 服务端（Worker + 中继 API）

**语言：** [English](README.md) | 简体中文

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/runsli/ZeroRelay/tree/main/server)

## 一键部署（推荐）

1. 点击上方 **Deploy to Cloudflare**（必须指向 **`server/`**）。
2. 登录 Cloudflare 并授权 GitHub。
3. 确认 fork / Worker 名称 → Cloudflare 创建 **KV** 并执行 `npm run build`。
4. 在 Android 或 CLI 中将 `https://<你的-worker>.workers.dev` 设为中继地址。

健康检查：`curl https://<你的-worker>.workers.dev/health`

## 手动部署（CLI）

```bash
cd server
npm install
npx wrangler kv namespace create MESSAGE_KV   # 首次；将 id 写入 wrangler.toml
npm run deploy
```

需要 Node.js **22+**（Wrangler 4）。
