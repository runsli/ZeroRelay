# ZeroRelay server (Worker + relay API + web UI)

**Languages:** English | [简体中文](README.zh-CN.md)

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/runsli/ZeroRelay/tree/main/server)

## One-click deploy (recommended)

1. Click **Deploy to Cloudflare** above (must point at **`server/`**, not `web/`).
2. Sign in to Cloudflare and authorize GitHub.
3. Confirm fork / Worker name → Cloudflare creates **KV** and runs `npm run build` (builds `../web` into `public/`).
4. Open `https://<your-worker>.workers.dev` — browser UI and relay share this URL.

Health: `curl https://<your-worker>.workers.dev/health`

## Why not Cloudflare Pages?

The official [Deploy to Cloudflare](https://developers.cloudflare.com/workers/platform/deploy-buttons/) button **does not support Pages-only apps**. This project ships the web client as **Worker static assets** (`[assets]` in `wrangler.toml`). Optional separate Pages setup: [web/README.md](../web/README.md).

## Manual deploy (CLI)

```bash
cd server
npm install
npx wrangler kv namespace create MESSAGE_KV   # once; add id to wrangler.toml
npm run deploy
```

Requires Node.js **22+** (Wrangler 4).
