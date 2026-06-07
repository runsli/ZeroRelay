# 可维护性路线图

**语言：** [English](MAINTAINABILITY.md) | 简体中文 · [贡献指南](../CONTRIBUTING.zh-CN.md)

Epic **#37** 在 UX Phase 4（#24）之后跟踪：自动化防回归、双语规范、以及更安全的重构。已立项各阶段（#25–#36）均已**完成**。

## 目标

| 方向 | 成果 |
|------|------|
| 质量门禁 | PR CI 在合并前拦截编译、lint、互操作与文案漂移 |
| 协作 | CONTRIBUTING、PR 模板、Dependabot、Node 22 |
| 测试金字塔 | Android JVM 加密测试、CLI 单元测试、互操作脚本 |
| 国际化与错误 | 类型化 `DataError`、CLI ↔ Android 错误 manifest 一致性 |
| 结构 | `HomeViewModel` 拆分为独立 action 类 |

## 阶段（已完成）

### Phase 0 — 质量门禁

| Issue | 交付物 |
|-------|--------|
| [#25](https://github.com/runsli/ZeroRelay/issues/25) | `.github/workflows/android-pr.yml` — `android/**` PR 上编译、lint、单元测试 |
| [#26](https://github.com/runsli/ZeroRelay/issues/26) | `.github/workflows/interop-test.yml` — 协议/加密路径上 `npm run test:interop` |
| [#27](https://github.com/runsli/ZeroRelay/issues/27) | `scripts/check-android-strings.sh` — Android PR CI 中英文 `strings.xml` key 一致 |
| [#28](https://github.com/runsli/ZeroRelay/issues/28) | `.github/workflows/server-pr.yml` — `server/**` PR 上 `npm run check`（`tsc`） |

### Phase 1 — 协作与依赖

| Issue | 交付物 |
|-------|--------|
| [#30](https://github.com/runsli/ZeroRelay/issues/30) | `CONTRIBUTING.md`、PR 模板、Issue 表单 |
| [#29](https://github.com/runsli/ZeroRelay/issues/29) | `.github/dependabot.yml`（Gradle + npm）、Node 22（`.nvmrc`、`engines`） |

### Phase 2 — 测试金字塔

| Issue | 交付物 |
|-------|--------|
| [#31](https://github.com/runsli/ZeroRelay/issues/31) | Android PR CI：`AccountBackupTest`、`GroupExchangeTest` |
| [#32](https://github.com/runsli/ZeroRelay/issues/32) | `MessageCipherTest`、`ContactExchangeTest`、`TlsPinStoreTest` |
| [#33](https://github.com/runsli/ZeroRelay/issues/33) | Security audit CI：`cli-menu`、`cli-config` 单元测试 |

### Phase 3 — 国际化与错误

| Issue | 交付物 |
|-------|--------|
| [#34](https://github.com/runsli/ZeroRelay/issues/34) | `DataError`、`UserErrorMapping`；`check-data-exception-messages.sh` |
| [#35](https://github.com/runsli/ZeroRelay/issues/35) | `docs/error-manifest.json`、`npm run check:errors`、Error manifest parity CI |

### Phase 4 — 结构

| Issue | 交付物 |
|-------|--------|
| [#36](https://github.com/runsli/ZeroRelay/issues/36) | `HomeViewModel` 拆为 action 类；home 包单元测试 |

## 首个里程碑 ✓

- [x] PR Android 编译通过（#25）
- [x] interop-test 纳入 CI（#26）
- [x] 文案 key 一致性纳入 CI（#27）
- [x] CONTRIBUTING 发布（#30）

## CI 矩阵（速查）

| 工作流 | 触发路径 | 执行内容 |
|--------|----------|----------|
| **Android PR check** | `android/**` | 文案 key → `testDebugUnitTest` → `lintDebug` |
| **Server PR check** | `server/**` | `npm run check` |
| **Interop test** | `cli*.js`、`server/**`、`interop-test.js`、`android/**/crypto/**` | 中继 + CLI 协议测试 |
| **Security audit** | 所有 PR | `npm test` + `npm audit` |
| **Error manifest parity** | 错误 manifest / `user_error_*` / `cli-errors.js` | `npm run check:errors` |

本地命令详见 [CONTRIBUTING.zh-CN.md](../CONTRIBUTING.zh-CN.md)。

## 本地验证清单

较大改动（Android 或协议）前建议：

```bash
bash scripts/check-android-strings.sh
bash scripts/check-data-exception-messages.sh
npm test
npm run check:errors
cd server && npm run check
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug
# 需本地中继：
ZERO_RELAY_SERVER=http://127.0.0.1:8787 npm run test:interop
```

## 后续待立项

暂在此跟踪，待单独开 issue：

- 拆分 `cli.js` 命令路由
- `docs/ARCHITECTURE.md`（中英）
- CI 接入 detekt + ESLint
- `CHANGELOG.md` 与发布 checklist

## 相关

- [PROTOCOL.zh-CN.md](PROTOCOL.zh-CN.md) — 线格式与错误 manifest
- [error-manifest.json](error-manifest.json) — CLI ↔ Android 错误 key 映射
