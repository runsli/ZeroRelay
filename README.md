# ZeroRelay

**End-to-end encrypted chat** with a relay you can run yourself. Messages are encrypted on your device; the server only stores and forwards **ciphertext**.

**Languages:** English · [简体中文](README.zh-CN.md) · [Docs](docs/README.md) · [文档](docs/README.zh-CN.md)

[![Deploy relay to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/runsli/ZeroRelay/tree/main/server)
[![GitHub Releases](https://img.shields.io/github/v/release/runsli/ZeroRelay?label=Android%20APK)](https://github.com/runsli/ZeroRelay/releases)

| Client | Platform |
|--------|----------|
| **Android** | Jetpack Compose · Material 3 |
| **CLI** | Node.js terminal |
| **Relay** | Node.js locally · Cloudflare Workers in production |

## Screenshots

> Add images under [`docs/screenshots/`](docs/screenshots/README.md) — README links below light up once files exist.

| Android | CLI |
|---------|-----|
| ![Android chat](docs/screenshots/android-chat.png) | ![CLI](docs/screenshots/cli-menu.png) |

*Optional:* [android-home](docs/screenshots/android-home.png) · [android-settings](docs/screenshots/android-settings.png)

---

## Quick start (about 5 minutes)

You need **a relay URL** (HTTPS) and **a client**. Everyone using the same relay can chat with each other.

### 1. Run a relay (or use someone else’s)

**Easiest:** click **Deploy to Cloudflare** above → sign in → deploy. You get a relay URL, e.g. `https://zero-relay-server.<you>.workers.dev`.

Check: `curl https://<your-worker>.workers.dev/health`

More options (local dev, custom domain, Wrangler CLI): [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) · [中文](docs/DEPLOYMENT.zh-CN.md) · [server/README.md](server/README.md) · [中文](server/README.zh-CN.md)

### 2. Install the Android app

Download the latest **`zerorelay-v*.apk`** from **[GitHub Releases](https://github.com/runsli/ZeroRelay/releases)**.

Or build Debug yourself: `cd android && ./gradlew assembleDebug` (see [Build from source](#build-from-source)).

**UI language:** The Android app follows your **system locale** — English by default (`values/`), Simplified Chinese when the device is set to Chinese (`values-zh-rCN/`).

### 3. Start chatting

1. Open the app → **Settings** → set **relay URL** to your `https://…` address → **Test connection** (trust the certificate pin on first connect if prompted).
2. Share your contact QR / link with a friend (they need the **same relay URL**).
3. Add each other, compare the **safety number**, mark verified, then send messages.

**CLI:** `git clone https://github.com/runsli/ZeroRelay.git && ./scripts/cli-setup.sh` → `zerorelay` (numbered menu) or `zerorelay config set server https://your-relay.example.com`

---

## What the relay sees (and does not)

| Relay sees | Relay does **not** see |
|------------|-------------------------|
| Encrypted blobs, delivery metadata | Message plaintext |
| `routeHash`, timing, sizes | Your private keys |
| Short-lived KV cache (default 2h) | Long-term chat history |

Details: [docs/SECURITY.md](docs/SECURITY.md) · [docs/PROTOCOL.md](docs/PROTOCOL.md)

---

## Features

- **E2EE:** X25519, HKDF, ratchet v2 (AES-256-GCM); group chats with shared keys
- **Relay hardening:** HMAC room tokens, Ed25519 signatures, rate limits
- **Android:** WebSocket + HTTP fallback, optional Material You colors, adaptive layout
- **Interop:** Android and CLI on the same protocol

---

## Build from source

| Goal | Where to look |
|------|----------------|
| Relay (local) | Node.js **22+** → `cd server && npm install && npm start` · [server/README.md](server/README.md) · [中文](server/README.zh-CN.md) |
| Android (Debug) | JDK 17+ (26 recommended), SDK 37 → `cd android && ./gradlew assembleDebug` |
| Android (Release APK) | [docs/GITHUB_RELEASES.md](docs/GITHUB_RELEASES.md) · [中文](docs/GITHUB_RELEASES.zh-CN.md) |
| CLI | Node.js **22+** → `./scripts/cli-setup.sh` · `zerorelay help` |
| Protocol / interop tests | [docs/PROTOCOL.md](docs/PROTOCOL.md) · [中文](docs/PROTOCOL.zh-CN.md) · `npm run test:interop` |
| All docs (bilingual index) | [docs/README.md](docs/README.md) |

Repository layout:

```
ZeroRelay/
├── android/     # Android app
├── server/      # Relay (Worker + local Node)
└── cli.js       # CLI entry
```

---

## For maintainers & contributors

See **[CONTRIBUTING.md](CONTRIBUTING.md)** · [中文](CONTRIBUTING.zh-CN.md) for interop tests, CI checks, and commit conventions.

- **Publish Android release:** bump `android/version.properties`, push tag `vX.Y.Z` → [docs/GITHUB_RELEASES.md](docs/GITHUB_RELEASES.md) · [中文](docs/GITHUB_RELEASES.zh-CN.md)
- **F-Droid / reproducible builds:** [docs/F-DROID.md](docs/F-DROID.md) · [中文](docs/F-DROID.zh-CN.md)
- **Full deployment checklist:** [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) · [中文](docs/DEPLOYMENT.zh-CN.md)
- **Security audit:** `npm run audit:all`

Signing keys (`*.jks`, `keystore.properties`) never belong in Git.

---

## License

MIT — see [LICENSE](LICENSE).
