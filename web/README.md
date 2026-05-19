# ZeroRelay Web

Browser client (MVP) aligned with [docs/PROTOCOL.md](../docs/PROTOCOL.md) — same tunnel `POST /t`, WebSocket auth, v2 ratchet, and contact QR payloads as the CLI and Android app.

## Requirements

- Node.js **18+**
- A running relay (`cd server && npm start` for local, or Cloudflare Workers in production)

## Develop

```bash
cd web
npm install
npm run dev
```

Open http://localhost:5173

1. Set server URL to `http://127.0.0.1:8787` (Vite proxies `/relay` → local relay).
2. Share your QR / link with another client (CLI, Android, or Web).
3. Add their contact payload, mark **verified** after checking the safety number, then chat.

## Build

```bash
npm run build
# static files in web/dist/
```

Serve `dist/` behind **HTTPS** in production.

---

## Deploy to Cloudflare

### One-click (recommended): same button as the relay

Cloudflare’s [Deploy to Cloudflare](https://developers.cloudflare.com/workers/platform/deploy-buttons/) button **does not support Pages-only apps**. This repo instead **bundles the web UI into the Worker** (`server/public` from `npm run build:web`).

Use the button in the root [README](../README.md) pointing at **`server/`**. After deploy:

1. Open `https://<your-worker>.workers.dev` in a browser.
2. The UI auto-uses that host as the relay (no `VITE_DEFAULT_RELAY_URL` required).

Manual: `cd server && npm run deploy` (builds `../web` first).

---

### Optional: Cloudflare Pages only

The web app is a **static SPA**. You can still host it on **Pages** separately from the Worker if you prefer two URLs.

### Architecture

| Piece | Cloudflare product | URL example |
|-------|-------------------|-------------|
| Relay (API + WebSocket) | **Workers** | `https://zero-relay-server.<account>.workers.dev` |
| Web UI | **Pages** | `https://zerorelay-web.pages.dev` |

The browser page must call an **HTTPS** relay (Workers URL or custom domain). CORS on the relay already allows browser clients (`Access-Control-Allow-Origin: *`).

### Option A — Dashboard (recommended)

1. Deploy the relay first ([README](../README.md) → Cloudflare Workers, or **Deploy to Cloudflare** button on `server/`).
2. Copy the Worker URL, e.g. `https://zero-relay-server.your-name.workers.dev`.
3. Cloudflare dashboard → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**.
4. Build settings:

   | Setting | Value |
   |---------|--------|
   | Root directory | `web` (or repo root with build command below) |
   | Build command | `npm ci && npm run build` |
   | Build output | `dist` |
   | Node version | 20 |

   If the repo root is the project root, use:

   - Build command: `cd web && npm ci && npm run build`
   - Output directory: `web/dist`

5. **Settings → Environment variables** (Production):

   | Name | Value |
   |------|--------|
   | `VITE_DEFAULT_RELAY_URL` | Your Worker HTTPS URL (no trailing slash) |

6. Redeploy. Opening the Pages URL should land on the home screen (relay pre-configured). Users can still change the relay under **更改**.

### Option B — Wrangler CLI

```bash
cd web
npm ci
VITE_DEFAULT_RELAY_URL=https://zero-relay-server.your-name.workers.dev npm run build
npx wrangler pages project create zerorelay-web   # once
npx wrangler pages deploy dist --project-name zerorelay-web
```

Set `VITE_DEFAULT_RELAY_URL` in the Pages dashboard for Git-based builds so you do not have to pass it on every local deploy.

### Custom domain (optional)

- **Pages**: add `chat.example.com` → your Pages project.
- **Worker**: add `relay.example.com` → your Worker route.
- Set `VITE_DEFAULT_RELAY_URL=https://relay.example.com` and redeploy Pages.

### What works after deploy

- Open the Pages URL → identity + QR shown (if `VITE_DEFAULT_RELAY_URL` is set).
- Add contacts, verify safety number, 1:1 chat with CLI / Android / other Web tabs.
- Keys stay in the browser **IndexedDB** on that device (not on Cloudflare).

### Limitations

- Web and Worker are **two** deploys (unless you later merge static assets into the Worker).
- No CLI-style TLS pinning; the browser uses its normal CA trust.
- Group chat not in Web MVP yet.

---

## Scope (MVP)

| Feature | Status |
|---------|--------|
| Identity + IndexedDB storage | Yes |
| Contact QR add / safety number | Yes |
| Direct 1:1 chat (ratchet v2) | Yes |
| WebSocket + HTTP poll fallback | Yes |
| Cloudflare Pages + preset relay URL | Yes (`VITE_DEFAULT_RELAY_URL`) |
| Group chat | Not yet |
| Passphrase-encrypted identity | Not yet |
| TLS pin (CLI-style TOFU) | Browser trust store only |

## Security notes

Private keys stay in **IndexedDB** on this device. Use HTTPS relays in production. The web client does not implement CLI-style certificate pinning; rely on the browser CA store and your relay hostname.
