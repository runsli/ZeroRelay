# Deployment guide

**Languages:** English | [简体中文](DEPLOYMENT.zh-CN.md)

Production relay: **Cloudflare Workers + KV**. Clients use **HTTPS** and **WSS** (`POST /t` + `/ws`). See [PROTOCOL.md](PROTOCOL.md) and [SECURITY.md](SECURITY.md).

```
┌─────────────┐     HTTPS POST /t      ┌──────────────────────────┐
│ Android /   │ ──────────────────────►│ Cloudflare Workers       │
│ Web / CLI   │     WSS /ws            │ + KV (ciphertext cache) │
└─────────────┘                        └──────────────────────────┘
       │                                         │
       └─ plaintext stays on devices ────────────┘ relay: routeHash + ciphertext only
```

| Stage | Goal | Command |
|-------|------|---------|
| Local | Dev / interop | `cd server && npm start` |
| Staging | Workers runtime | `cd server && npm run dev` |
| Production | Public relay | `cd server && npm run deploy` |

---

## One-click Deploy (relay + web UI)

Use the Deploy button in [README.md](../README.md) (points at `server/`). After deploy:

- Browser: `https://<worker>.workers.dev`
- Health: `curl https://<worker>.workers.dev/health`
- Android / CLI: same HTTPS URL in settings

Do **not** use a Pages-only one-click deploy for `web/` — the UI is bundled into the Worker (`server/public`).

Fork? Run `./scripts/deploy-button-url.sh` for your repo URL.

---

## Local relay

```bash
cd server && npm install && npm start
# http://localhost:8787
```

| Variable | Description |
|----------|-------------|
| `PORT` | Listen port (default `8787`) |
| `MESSAGE_TTL_SEC` / `MAX_MESSAGES_PER_ROOM` | Same as Workers |
| `ENABLE_LEGACY_HTTP` | `1` enables legacy JSON endpoints locally |
| `TRANSPORT_LOG` / `RELAY_LOG` | `1` for logs |

Verify: `curl -s http://127.0.0.1:8787/` and `npm run test:interop` (repo root).

| Client | URL |
|--------|-----|
| Emulator | `http://10.0.2.2:8787` |
| Device on LAN | `http://<LAN-IP>:8787` |

---

## Manual Cloudflare Workers deploy

**Prerequisites:** Cloudflare account, Node.js **≥ 22**, `cd server && npm install`

```bash
cd server && npx wrangler login
npx wrangler kv namespace create MESSAGE_KV
# Add namespace id to wrangler.toml — see wrangler.toml.example
npm run check && npm run deploy
```

Custom domain: Workers dashboard → **Domains & Routes**. Clients use `https://your-domain` (WSS included).

### Worker `[vars]`

| Variable | Default | Description |
|----------|---------|-------------|
| `MESSAGE_TTL_SEC` | `7200` | KV TTL (seconds) |
| `MAX_MESSAGES_PER_ROOM` | `100` | Max messages per room |
| `ENABLE_LEGACY_HTTP` | `0` | `1` = legacy `/send`, `/messages` |
| `RELAY_LOG` | `0` | `1` = error logs |

Secrets: `wrangler secret put <NAME>`. Do not commit `.dev.vars` or `server/.wrangler/`.

---

## Connect clients

### Android (Release)

1. HTTPS relay URL only (no cleartext).
2. Settings → server URL → **Test connection** → trust TLS pin if asked.
3. Verify the safety number before chatting.

### CLI

```bash
zerorelay config set server https://relay.example.com
zerorelay config test
zerorelay config trust-pin   # if needed
```

---

## Pre-launch checklist

- [ ] `ENABLE_LEGACY_HTTP=0`; clients use `POST /t` + `wss`
- [ ] No signing keys in Git; `RELAY_LOG=0` in production
- [ ] HTTPS domain valid
- [ ] `npm run audit:all` clean
- [ ] `npm run test:interop` against production relay
- [ ] Users know: relay sees metadata (timing, sizes, `routeHash`); message bodies are E2EE

KV retention: 2h / 100 messages per room (defaults).
