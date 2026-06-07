# Maintainability roadmap

**Languages:** English · [简体中文](MAINTAINABILITY.zh-CN.md) · [Contributing](../CONTRIBUTING.md)

Epic **#37** tracks automated regression prevention, bilingual discipline, and safer refactors after UX Phase 4 (#24). All filed phases (#25–#36) are **complete** as of this document.

## Goal

| Area | Outcome |
|------|---------|
| Quality gates | PR CI catches compile, lint, interop, and string drift before merge |
| Collaboration | CONTRIBUTING, PR template, Dependabot, Node 22 |
| Test pyramid | JVM crypto tests (Android), CLI unit tests, interop harness |
| i18n & errors | Typed `DataError`, manifest parity CLI ↔ Android |
| Structure | `HomeViewModel` split into focused action classes |

## Phases (completed)

### Phase 0 — Quality gates

| Issue | Deliverable |
|-------|-------------|
| [#25](https://github.com/runsli/ZeroRelay/issues/25) | `.github/workflows/android-pr.yml` — `compileDebugKotlin`, `lintDebug`, unit tests on `android/**` PRs |
| [#26](https://github.com/runsli/ZeroRelay/issues/26) | `.github/workflows/interop-test.yml` — `npm run test:interop` on protocol/crypto paths |
| [#27](https://github.com/runsli/ZeroRelay/issues/27) | `scripts/check-android-strings.sh` — en/zh `strings.xml` key parity in Android PR CI |
| [#28](https://github.com/runsli/ZeroRelay/issues/28) | `.github/workflows/server-pr.yml` — `npm run check` (`tsc`) on `server/**` PRs |

### Phase 1 — Collaboration & deps

| Issue | Deliverable |
|-------|-------------|
| [#30](https://github.com/runsli/ZeroRelay/issues/30) | `CONTRIBUTING.md`, PR template, issue forms |
| [#29](https://github.com/runsli/ZeroRelay/issues/29) | `.github/dependabot.yml` (Gradle + npm), Node 22 (`.nvmrc`, `engines`) |

### Phase 2 — Test pyramid

| Issue | Deliverable |
|-------|-------------|
| [#31](https://github.com/runsli/ZeroRelay/issues/31) | `AccountBackupTest`, `GroupExchangeTest` in Android PR CI |
| [#32](https://github.com/runsli/ZeroRelay/issues/32) | `MessageCipherTest`, `ContactExchangeTest`, `TlsPinStoreTest` |
| [#33](https://github.com/runsli/ZeroRelay/issues/33) | `test/cli-menu.test.js`, `test/cli-config.test.js` in Security audit CI |

### Phase 3 — i18n & errors

| Issue | Deliverable |
|-------|-------------|
| [#34](https://github.com/runsli/ZeroRelay/issues/34) | `DataError`, `UserErrorMapping`; `check-data-exception-messages.sh` |
| [#35](https://github.com/runsli/ZeroRelay/issues/35) | `docs/error-manifest.json`, `npm run check:errors`, Error manifest parity CI |

### Phase 4 — Structure

| Issue | Deliverable |
|-------|-------------|
| [#36](https://github.com/runsli/ZeroRelay/issues/36) | `HomeViewModel` → action classes (`RelaySettingsActions`, `BackupActions`, `ContactGroupActions`, etc.); home package unit tests |

## First milestone ✓

- [x] PR Android compile green (#25)
- [x] interop-test in CI (#26)
- [x] strings key parity in CI (#27)
- [x] CONTRIBUTING published (#30)

## CI matrix (quick reference)

| Workflow | Trigger paths | What it runs |
|----------|---------------|--------------|
| **Android PR check** | `android/**` | String parity → `testDebugUnitTest` → `lintDebug` |
| **Server PR check** | `server/**` | `npm run check` |
| **Interop test** | `cli*.js`, `server/**`, `scripts/interop-test.js`, `android/**/crypto/**` | Relay + CLI protocol tests |
| **Security audit** | all PRs | `npm test` + `npm audit` |
| **Error manifest parity** | error manifest / `user_error_*` / `cli-errors.js` | `npm run check:errors` |

Full local commands: [CONTRIBUTING.md](../CONTRIBUTING.md).

## Local verification checklist

Before a large Android or protocol change:

```bash
bash scripts/check-android-strings.sh
bash scripts/check-data-exception-messages.sh
npm test
npm run check:errors
cd server && npm run check
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug
# With local relay:
ZERO_RELAY_SERVER=http://127.0.0.1:8787 npm run test:interop
```

## Future backlog (not filed)

Tracked here until separate issues exist:

- Split `cli.js` command router into modules
- `docs/ARCHITECTURE.md` (EN + ZH)
- detekt + ESLint in CI
- `CHANGELOG.md` + release checklist

## Related

- [PROTOCOL.md](PROTOCOL.md) — wire formats and error manifest pointer
- [error-manifest.json](error-manifest.json) — CLI ↔ Android error key map
