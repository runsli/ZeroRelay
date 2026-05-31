# ZeroRelay server (Worker + relay API)

**Languages:** English | [简体中文](README.zh-CN.md)

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/runsli/ZeroRelay/tree/main/server)

## One-click deploy (recommended)

1. Click **Deploy to Cloudflare** above (must point at **`server/`**).
2. Sign in to Cloudflare and authorize GitHub.
3. Confirm fork / Worker name → Cloudflare creates **KV** and runs `npm run build`.
4. Use `https://<your-worker>.workers.dev` as the relay URL in Android or CLI.

Health: `curl https://<your-worker>.workers.dev/health`

## Manual deploy (CLI)

```bash
cd server
npm install
npx wrangler kv namespace create MESSAGE_KV   # once; add id to wrangler.toml
npm run deploy
```

Requires Node.js **22+** (Wrangler 4).
