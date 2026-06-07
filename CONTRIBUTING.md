# Contributing to ZeroRelay

Thank you for helping improve ZeroRelay. This guide covers the essentials for local development and CI checks.

**Languages:** English · [简体中文](CONTRIBUTING.zh-CN.md)

## Environment

| Component | Version |
|-----------|---------|
| Node.js | 22 LTS (CLI + server + interop tests) |
| JDK | 21 (Android CI; 17+ for local Gradle) |
| Android SDK | API 37 (see `android/app/build.gradle.kts`) |

## Run interop tests locally

Interop tests verify CLI↔relay protocol compatibility (direct chat, group chat, WebSocket, backup formats).

```bash
# Terminal 1 — local relay
cd server && npm ci && npm start

# Terminal 2 — tests (repo root)
npm ci
ZERO_RELAY_SERVER=http://127.0.0.1:8787 npm run test:interop
```

Default server URL is `http://127.0.0.1:8787` if `ZERO_RELAY_SERVER` is unset.

## CI checks (pull requests)

| Check | When it runs |
|-------|----------------|
| **Android PR check** | PRs that change `android/**` — `compileDebugKotlin` + `lintDebug` |
| **Interop test** | PRs that change `cli*.js`, `server/**`, `scripts/interop-test.js`, or `android/**/crypto/**` |
| **Security audit** | All PRs — `npm audit` on root and `server/` |

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for protocol details.

## Android strings (i18n)

User-facing copy lives in two files — keep keys in sync:

- `android/app/src/main/res/values/strings.xml` (English default)
- `android/app/src/main/res/values-zh-rCN/strings.xml` (Simplified Chinese)

## Commit messages

Use concise prefixes:

- `feat(android):` — Android app features
- `feat(cli):` / `ci(android):` / `ci:` — CLI or CI changes
- `fix(server):` — relay fixes
- `docs:` — documentation only

Reference issues with `Closes #NN` when applicable.

## More documentation

- [docs/README.md](docs/README.md) — full bilingual doc index
- [docs/SECURITY.md](docs/SECURITY.md) — security and backup practices
- [README.md](README.md) — build from source table
