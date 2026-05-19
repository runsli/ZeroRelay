# ZeroRelay — end-to-end encrypted chat

**Languages:** English | [简体中文](README.zh-CN.md) · [All docs](docs/README.md)

Cross-platform secure chat: **Android** (Jetpack Compose + Material 3) and **Node.js / Cloudflare Workers** relay. Messages are AES-256-GCM encrypted on clients; the server forwards ciphertext only.

## Architecture

```
ZeroRelay/
├── android/          # Jetpack Compose client (Kotlin)
├── server/           # Node.js / TypeScript relay
├── web/              # Browser client (Vite + TypeScript)
└── cli.js            # Command-line client (Node.js)
```

| Module | Stack |
|--------|--------|
| **Android** | Kotlin, Jetpack Compose, Material 3, OkHttp (WebSocket receive + HTTP send/poll) |
| **Web** | TypeScript, Vite, Web Crypto, IndexedDB |
| **Server** | Node.js, Hono, Cloudflare Workers |
| **CLI** | Node.js |

> Legacy Flutter client was removed; the **Web** app is a new TypeScript client in `web/`. Material 3 uses [Compose Material3](https://developer.android.com/jetpack/androidx/releases/compose-material3); brand seed `#0F9D47` in `android/.../ui/theme/Theme.kt`.

## Features

- **E2EE:** direct X25519 + HKDF session keys, message ratchet v2 (AES-256-GCM); groups use shared key + versioned envelopes
- **Relay security:** room HMAC token (client derives `routeHash` locally), Ed25519 message signatures, rate limits and size caps; CLI supports TLS TOFU pin and optional passphrase-encrypted identity
- **Zero knowledge:** server relays ciphertext only
- **Realtime:** Android WebSocket + HTTP long-poll fallback; CLI over HTTP/tunnel
- **Material 3:** `MaterialExpressiveTheme`, expressive motion; optional **Material You** dynamic color (Android 12+)
- **Adaptive UI:** `NavigationRail` from 600dp; list-detail from 840dp

## Quick start

### Relay (local)

```bash
cd server
npm install
npm start
# http://localhost:8787
```

### Android

**Install (recommended):** download the latest **Release APK** from this repo’s **GitHub Releases** page (built and signed by the project maintainer).

**Build from source** if you want your own binary (fork, patches, or self-hosted updates) — see [Android Release build](#4-android-release-build) below.

**Requirements (for building):** JDK **26** (recommended; Gradle 9.4) or 17+, **Android SDK 37**, Android Studio

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 26)   # macOS example
cd android
./gradlew assembleDebug
```

| Layer | Version | Role |
|-------|---------|------|
| Gradle runtime | **JDK 26** | Run `./gradlew` |
| Toolchain | JDK 26 | `java.toolchain` |
| App bytecode | **JVM 17** | On device (`jvmTarget = 17`) |

Set `org.gradle.java.home` in `android/local.properties` (see `local.properties.example`).

| Environment | Server URL |
|-------------|------------|
| Emulator | `http://10.0.2.2:8787` (app default) |
| Physical device | `http://<LAN-IP>:8787` |

Debug allows HTTP; **Release requires HTTPS**.

### Web

Browser UI (MVP): see [web/README.md](web/README.md).

```bash
cd web && npm install && npm run dev
# or from repo root: npm run dev:web
```

**One-click (recommended):** use the Deploy button on `server/` — web UI is built into the Worker. **Optional:** separate [Cloudflare Pages](web/README.md#deploy-to-cloudflare-pages-open-and-use) deploy for `web/` only.

### CLI

**New machine (one command):**

```bash
git clone <repo-url> && cd chat
./scripts/cli-setup.sh          # npm install + optional global `zerorelay` / `zr`
zerorelay help
zerorelay config set server https://relay.example.com
zerorelay                          # interactive menu
```

Without global link: `./zerorelay help` or `npm run setup:cli -- --no-link` then add the repo to `PATH`.

```bash
node cli.js http://localhost:8787   # same as zerorelay
node cli.js help
# Optional: encrypt ~/.zero-relay/identity.enc.json
export ZERO_RELAY_PASSPHRASE='your-passphrase'
```

Identity, config, and TLS pin files under `~/.zero-relay/` are written with mode `0600` / directory `0700`.

Chats use **WebSocket receive + HTTP long-poll fallback** (same as Android). Protocol constants: [docs/PROTOCOL.md](docs/PROTOCOL.md).

Common commands: `contact list` / `contact verify <id>`, `config show`, `config test`, `group create`, `ratchet export --clipboard` (interoperable with the app).

Debug: `CRYPTO_LOG=1 node cli.js chat <id>` (matches Android Debug `ZeroRelay.Crypto` logs).

**P3:** `/detach` for background receive; `node cli.js qr --png ./qr.png`; `npm run build:cli` for a single-file CLI (`pkg` dev dependency).

**Interop tests** (start relay first):

```bash
cd server && npm start   # terminal 1
npm run test:interop     # repo root, terminal 2
```

## Android layout

```
android/app/src/main/kotlin/app/zerorelay/
├── MainActivity.kt
├── ui/          # AppRoot, HomeScreen, ChatScreen, Theme (#0F9D47)
└── data/        # crypto, ChatRepository, IdentityStore, ServerUrl
```

## Usage

1. Start `server` (local or Cloudflare Workers)
2. Android: set server URL → create identity / scan contact → direct or group chat
3. CLI: `node cli.js <server-url>` → `contact add` / `group create` → `chat`

## Deployment

Production: **Cloudflare Workers + KV** relay; clients use **HTTPS / WSS** (`POST /t` tunnel + `/ws`). See [docs/PROTOCOL.md](docs/PROTOCOL.md) and pre-launch [docs/SECURITY.md](docs/SECURITY.md).

### Overview

```
┌─────────────┐     HTTPS POST /t      ┌──────────────────────────┐
│ Android /   │ ──────────────────────►│ Cloudflare Workers       │
│ CLI         │     WSS /ws            │ + KV (ciphertext cache)  │
└─────────────┘                        └──────────────────────────┘
       │                                         │
       └─ E2EE plaintext only on device ─────────┘ relay sees routeHash + ciphertext
```

| Stage | Goal | Command |
|-------|------|---------|
| 1. Local | Dev / interop | `cd server && npm start` |
| 2. Staging | Workers runtime | `cd server && npm run dev` |
| 3. Production | Public relay | `npm run check && npm run deploy` |
| 4. Clients | HTTPS + TLS pin | App settings / `cli.js config` |

### One-click Deploy to Cloudflare (relay + web UI)

Push this repo to a **public GitHub** repository, then use the official [Deploy to Cloudflare](https://developers.cloudflare.com/workers/platform/deploy-buttons/) button on **`server/`**: sign in → authorize Git → auto-create Worker, **KV namespace**, build the web app, and deploy (relay API + static web UI on **one HTTPS URL**).

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/runsli/ZeroRelay/tree/main/server)

After deploy, open `https://<your-worker>.workers.dev` in a browser — the chat UI loads and uses the same host as the relay (**no Cloudflare Pages project**). Health check: `curl https://<your-worker>.workers.dev/health`.

> **Do not** use a Pages-only deploy for `web/` — Cloudflare’s Deploy button does not support that. This repo bundles the web UI into the Worker (`server/public`).  
> Fork? Regenerate the button URL: `./scripts/deploy-button-url.sh`

**Flow:**

1. Click button → Cloudflare login + GitHub authorization.
2. Confirm Worker name and fork repo name.
3. Platform **auto-creates `MESSAGE_KV`** from `server/wrangler.toml` (no manual KV id).
4. After build: `https://<worker>.<account>.workers.dev` — `curl` `/` to verify.
5. (Optional) Custom domain in dashboard; clients use **HTTPS** and complete TLS pin.

**Requirements:** public repo; self-contained `server/` directory. Private repos: use Wrangler CLI instead.

**vs CLI deploy:** button uses Workers Builds and writes KV id into your fork; local `wrangler deploy` needs `wrangler kv namespace create` and id in `wrangler.toml` (below).

---

### 1. Local relay development

1. **Install**

```bash
cd server && npm install
```

2. **Start** (`server-local.js`, port `8787`)

```bash
npm start
# or: npm run start:log
```

3. **Optional env** (repo `.env` or shell; see `.env.example`)

| Variable | Description |
|----------|-------------|
| `PORT` | Listen port (default `8787`) |
| `MESSAGE_TTL_SEC` / `MAX_MESSAGES_PER_ROOM` | Same as Workers |
| `ENABLE_LEGACY_HTTP` | `1` to enable legacy JSON endpoints locally |
| `TRANSPORT_LOG` | `1` for transport logs |
| `RELAY_LOG` | `1` for server errors |

4. **Verify**

```bash
curl -s http://127.0.0.1:8787/
npm run test:interop   # from repo root
```

Clients: emulator `http://10.0.2.2:8787`; device `http://<LAN-IP>:8787` (same Wi‑Fi).

---

### 2. Cloudflare Workers (production)

#### Prerequisites

- [Cloudflare](https://dash.cloudflare.com) account
- Node.js **≥ 22** (Wrangler 4; 22 LTS recommended)
- Wrangler via `cd server && npm install`

#### Step 1: Wrangler login

```bash
cd server && npx wrangler login
```

#### Step 2: KV namespace (CLI deploy only)

Dashboard → **Workers & Pages** → **KV** → **Create namespace**, or:

```bash
npx wrangler kv namespace create MESSAGE_KV
```

#### Step 3: `wrangler.toml`

[`server/wrangler.toml`](server/wrangler.toml) is committed for one-click deploy (KV id filled in your fork). **Manual CLI deploy:** add namespace id:

```bash
cd server
npx wrangler kv namespace create MESSAGE_KV
# Add to wrangler.toml:
# [[kv_namespaces]]
# binding = "MESSAGE_KV"
# id = "<id from above>"
```

See [`server/wrangler.toml.example`](server/wrangler.toml.example). Do not commit `.dev.vars`, `server/.wrangler/`, or `wrangler.local.toml`.

#### Step 4: Check and audit

```bash
npm run check && npm run audit
# or from repo root: npm run audit:all
```

#### Step 5: Local Workers dev (optional)

```bash
npm run dev
```

#### Step 6: Deploy

```bash
npm run deploy
```

#### Step 7: Custom domain (recommended)

Workers dashboard → **Domains & Routes** → e.g. `relay.example.com` (orange-cloud DNS). Clients use `https://relay.example.com` (**WSS** included).

#### Step 8: Smoke test

```bash
curl -s https://relay.example.com/
ZERO_RELAY_SERVER=https://relay.example.com npm run test:interop
```

#### Worker `[vars]`

| Variable | Default | Description |
|----------|---------|-------------|
| `MESSAGE_TTL_SEC` | `7200` | KV message TTL (seconds) |
| `MAX_MESSAGES_PER_ROOM` | `100` | Max messages per room |
| `ENABLE_LEGACY_HTTP` | `0` | `1` enables legacy `/send`, `/messages` |
| `RELAY_LOG` | `0` | `1` for error logs |
| `ENVIRONMENT` | `production` | Environment label |

Secrets: `wrangler secret put <NAME>`. Redeploy after changing `[vars]`.

#### Updates

```bash
cd server && npm run check && npm run deploy
```

Rollback via Dashboard → **Deployments**.

---

### 3. Connect clients to production

#### Android (Release)

1. **HTTPS** only (Release blocks cleartext).
2. Settings → server URL → **Test connection** → trust TLS pin if prompted.
3. Verify safety number before sending (Release cannot skip).

Debug: `http://10.0.2.2:8787` for local relay.

#### CLI

```bash
node cli.js config set server https://relay.example.com
node cli.js config test
node cli.js config trust-pin   # if pin mismatch
```

#### Group invites

HTTPS server URL is embedded in group QR payloads when configured.

---

### 4. Android Release build

#### How distribution works

| Who | What you get |
|-----|----------------|
| **Most users** | Install the **pre-built APK** from **GitHub Releases**. The maintainer builds and signs it locally and publishes it with each version tag. |
| **Developers / forks** | Clone the repo and run **`assembleRelease`** with **your own** signing key to produce an APK you control (updates, custom relay, patches). |

APKs signed with different keys are different apps to Android — you cannot install a self-built APK on top of the maintainer’s release without uninstalling first (or use the same key only if you own that keystore).

#### Signing (never in Git)

Release keystores and passwords stay **on the builder’s machine only** — they are not part of the open-source tree:

| File | In Git? |
|------|---------|
| `android/keystore.properties.example` | Yes (template) |
| `android/keystore.properties` | **No** (gitignored) |
| `*.jks` / `*.keystore` | **No** (gitignored) |

`scripts/verify-no-signing-in-repo.sh` runs in CI to block accidental commits of signing material.

#### Version numbers (reproducible / tag-driven)

Pinned in **`android/version.properties`** (committed). Bump before release:

```bash
scripts/android-version-from-tag.sh v1.0.1 --write
git add android/version.properties && git commit -m "chore(android): bump version to 1.0.1"
```

Gradle resolution order: `version.properties` → `-P` / env → exact git tag on `HEAD` → default `1.0.0` / `10000`. F-Droid notes: [docs/F-DROID.md](docs/F-DROID.md).

#### Maintainer: one-command release build

One-time: `cp android/keystore.properties.example android/keystore.properties` and add your `.jks` under `android/` (gitignored).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 26)   # if needed
scripts/android-release-build.sh v1.0.1
```

Output: `zerorelay-v1.0.1.apk` in the repo root with matching in-app version. Then:

```bash
git tag v1.0.1 && git push origin v1.0.1
# upload zerorelay-v1.0.1.apk to GitHub Releases for v1.0.1
```

Manual Gradle (same versions): `VERSION_NAME=1.0.1 VERSION_CODE=10001 ./gradlew -p android :app:assembleRelease`

#### Build your own Release APK

Same Gradle steps as above, with **your** `keystore.properties` and `.jks`. First-time example:

```bash
keytool -genkey -v -keystore android/zerorelay-release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias zerorelay
```

Then configure `android/keystore.properties` and run `assembleRelease`. Use your APK to update **only** installs that were signed with the same key.

#### CI (optional smoke builds)

[`.github/workflows/android-release.yml`](.github/workflows/android-release.yml) compiles on push to `main` / tags for **CI only** (debug-signed artifact, filename `*-ci-debug.apk`). On tag `v*`, CI uses the same `versionName` / `versionCode` as `android-release-build.sh`. It does **not** publish the official Release — upload your locally signed APK to **GitHub Releases**.

---

### 5. Pre-launch checklist

- [ ] `ENABLE_LEGACY_HTTP=0`; clients use `POST /t` + `wss` only
- [ ] No secrets in Git; Android `*.jks` / `keystore.properties` local only; `RELAY_LOG=0`
- [ ] HTTPS custom domain valid
- [ ] `npm run audit:all` clean
- [ ] `npm run test:interop` against production (or staging)
- [ ] Android Release TLS pin + CLI `config test`
- [ ] Users informed: relay sees metadata (`routeHash`, timing, sizes); plaintext is E2EE-only

Transport privacy (v2): `routeHash` auth; no `roomToken` or chat `roomId` on the wire; KV retention 2h / 100 msgs per room.

## Security

See [docs/SECURITY.md](docs/SECURITY.md). Local audit: `npm run audit:all`.

## License

MIT
