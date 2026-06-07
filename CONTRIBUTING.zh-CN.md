# 参与贡献 ZeroRelay

感谢参与改进 ZeroRelay。本文介绍本地开发与 CI 检查的要点。

**语言：** [English](CONTRIBUTING.md) · 简体中文

## 环境

| 组件 | 版本 |
|------|------|
| Node.js | **22 LTS**（CLI、server、interop 测试；根目录与 `server/package.json` 的 `engines`；`.nvmrc` 固定为 22） |
| JDK | 21（Android CI；本地 Gradle 17+ 亦可） |
| Android SDK | API 37（见 `android/app/build.gradle.kts`） |

在仓库根目录使用 Node 22：`nvm use`、`fnm use`，或安装 [nodejs.org](https://nodejs.org/) 22 LTS。

## 依赖更新

[Dependabot](https://docs.github.com/en/code-security/dependabot) 每周为以下生态开 PR：

- npm — 仓库根目录与 `server/`
- Gradle — `android/`（版本目录 `android/gradle/libs.versions.toml`）

## 本地运行 interop 测试

Interop 测试验证 CLI 与中继的协议互通（单聊、群聊、WebSocket、备份格式）。

```bash
# 终端 1 — 启动本地中继
cd server && npm ci && npm start

# 终端 2 — 运行测试（仓库根目录）
npm ci
ZERO_RELAY_SERVER=http://127.0.0.1:8787 npm run test:interop
```

未设置 `ZERO_RELAY_SERVER` 时，默认使用 `http://127.0.0.1:8787`。

## CLI 单元测试

```bash
npm test
```

覆盖 `cli-menu.js` 菜单路由与 `cli-config.js` 本地配置（Node 内置 `node:test`）。每个 PR 的 **Security audit** CI 都会执行。

CLI 与 Android 错误 key 一致性（manifest ↔ `cli-errors.js` / `UserErrorKind` / `user_error_*` 文案）：

```bash
npm run check:errors
```

见 [docs/error-manifest.json](docs/error-manifest.json)。**Error manifest parity** 工作流在相关路径改动时运行。

## Server TypeScript 检查

```bash
cd server && npm ci && npm run check
```

## 本地编译 Android

JDK 17+（推荐 21，与 CI 一致）。安装 Android SDK API 37，或在 `android/local.properties` 中设置 `sdk.dir`。

```bash
cd android
./gradlew :app:assembleDebug
```

单元测试（JVM，无需设备）：`./gradlew :app:testDebugUnitTest` — 覆盖加密与邀请解析（含 `MessageCipher`、`ContactExchange` 等），`android/**` PR 均会执行。

提 PR 前可选：`./gradlew :app:lintDebug`（与 **Android PR check** CI 相同）。

## 协议与加密变更

若修改消息格式、中继 HTTP/WebSocket 行为、常量，或 CLI/Android/server 共用的加密逻辑：

1. 更新 [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md) 与 [docs/PROTOCOL.md](docs/PROTOCOL.md)
2. 需要跨端保持一致时，扩展 [scripts/interop-test.js](scripts/interop-test.js)
3. 在本地中继上运行 `npm run test:interop`（见上文）

## CI 检查（Pull Request）

| 检查 | 触发条件 |
|------|----------|
| **Android PR check** | PR 改动 `android/**` — 文案 key 一致性、`compileDebugKotlin`、`testDebugUnitTest`、`lintDebug` |
| **Server PR check** | PR 改动 `server/**` — `npm run check`（`tsc`） |
| **Interop test** | PR 改动 `cli*.js`、`server/**`、`scripts/interop-test.js` 或 `android/**/crypto/**` |
| **Security audit** | 所有 PR — `npm test`（CLI 单元测试）+ 对根目录与 `server/` 执行 `npm audit` |
| **Error manifest parity** | PR 改动 `cli-errors.js`、`user_error_*` 文案或 [error-manifest.json](docs/error-manifest.json) |

协议细节见 [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md)。

## Android 文案（i18n）

面向用户的字符串需保持两个文件 **key 一致**：

- `android/app/src/main/res/values/strings.xml`（英文默认）
- `android/app/src/main/res/values-zh-rCN/strings.xml`（简体中文）

本地检查：`bash scripts/check-android-strings.sh`

`data/` 层使用 `DataError`（在 ViewModel 映射为 `UserError`），异常信息不得含中文。检查：`bash scripts/check-data-exception-messages.sh`

## Commit 信息

建议使用简短前缀：

- `feat(android):` — Android 功能
- `feat(cli):` / `ci(android):` / `ci:` — CLI 或 CI
- `fix(server):` — 中继修复
- `docs:` — 仅文档

适用时请写 `Closes #NN` 关联 issue。

## Pull Request

请使用 [PR 模板](.github/pull_request_template.md)填写测试计划、协议/加密影响、文案 checklist 与 CI 对应关系。

## 更多文档

- [docs/README.zh-CN.md](docs/README.zh-CN.md) — 双语文档索引
- [docs/SECURITY.zh-CN.md](docs/SECURITY.zh-CN.md) — 安全与备份
- [README.zh-CN.md](README.zh-CN.md) — 编译说明
