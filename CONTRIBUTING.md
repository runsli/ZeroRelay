# Contributing to ZeroRelay

Thank you for helping improve ZeroRelay. This guide covers the essentials for local development and CI checks.

**Languages:** English · [简体中文](CONTRIBUTING.zh-CN.md)

## Environment

| Component | Version |
|-----------|---------|
| Node.js | **22 LTS** for CLI, server, and interop tests (`engines` in root and `server/package.json`; `.nvmrc` pins 22) |
| JDK | 21 (Android CI; 17+ for local Gradle) |
| Android SDK | API 37 (see `android/app/build.gradle.kts`) |

Use Node 22 from the repo root: `nvm use`, `fnm use`, or install [nodejs.org](https://nodejs.org/) 22 LTS.

## Dependency updates

[Dependabot](https://docs.github.com/en/code-security/dependabot) opens weekly PRs for:

- npm — repo root and `server/`
- Gradle — `android/` (version catalog in `android/gradle/libs.versions.toml`)

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

## Server TypeScript check

```bash
cd server && npm ci && npm run check
```

## Build Android locally

JDK 17+ (21 recommended for parity with CI). Install Android SDK API 37 or set `sdk.dir` in `android/local.properties`.

```bash
cd android
./gradlew :app:assembleDebug
```

Optional before opening a PR: `./gradlew :app:lintDebug` (same as **Android PR check** CI).

## Protocol and crypto changes

If you change message formats, relay HTTP/WebSocket behavior, constants, or crypto used by CLI, Android, or server:

1. Update [docs/PROTOCOL.md](docs/PROTOCOL.md) and [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md)
2. Extend [scripts/interop-test.js](scripts/interop-test.js) when behavior should stay in sync across clients
3. Run `npm run test:interop` with a local relay (see above)

## CI checks (pull requests)

| Check | When it runs |
|-------|----------------|
| **Android PR check** | PRs that change `android/**` — string key parity, `compileDebugKotlin` + `lintDebug` |
| **Server PR check** | PRs that change `server/**` — `tsc` via `npm run check` |
| **Interop test** | PRs that change `cli*.js`, `server/**`, `scripts/interop-test.js`, or `android/**/crypto/**` |
| **Security audit** | All PRs — `npm audit` on root and `server/` |

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for protocol details.

## Android strings (i18n)

User-facing copy lives in two files — keep keys in sync:

- `android/app/src/main/res/values/strings.xml` (English default)
- `android/app/src/main/res/values-zh-rCN/strings.xml` (Simplified Chinese)

Verify locally: `bash scripts/check-android-strings.sh`

## Commit messages

Use concise prefixes:

- `feat(android):` — Android app features
- `feat(cli):` / `ci(android):` / `ci:` — CLI or CI changes
- `fix(server):` — relay fixes
- `docs:` — documentation only

Reference issues with `Closes #NN` when applicable.

## Pull requests

Use the [pull request template](.github/pull_request_template.md) — test plan, protocol/crypto impact, i18n checklist, and CI mapping.

## More documentation

- [docs/README.md](docs/README.md) — full bilingual doc index
- [docs/SECURITY.md](docs/SECURITY.md) — security and backup practices
- [README.md](README.md) — build from source table
