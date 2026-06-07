# 参与贡献 ZeroRelay

感谢参与改进 ZeroRelay。本文介绍本地开发与 CI 检查的要点。

**语言：** [English](CONTRIBUTING.md) · 简体中文

## 环境

| 组件 | 版本 |
|------|------|
| Node.js | 22 LTS（CLI、server、interop 测试） |
| JDK | 21（Android CI；本地 Gradle 17+ 亦可） |
| Android SDK | API 37（见 `android/app/build.gradle.kts`） |

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

## CI 检查（Pull Request）

| 检查 | 触发条件 |
|------|----------|
| **Android PR check** | PR 改动 `android/**` — `compileDebugKotlin` + `lintDebug` |
| **Interop test** | PR 改动 `cli*.js`、`server/**`、`scripts/interop-test.js` 或 `android/**/crypto/**` |
| **Security audit** | 所有 PR — 对根目录与 `server/` 执行 `npm audit` |

协议细节见 [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md)。

## Android 文案（i18n）

面向用户的字符串需保持两个文件 **key 一致**：

- `android/app/src/main/res/values/strings.xml`（英文默认）
- `android/app/src/main/res/values-zh-rCN/strings.xml`（简体中文）

## Commit 信息

建议使用简短前缀：

- `feat(android):` — Android 功能
- `feat(cli):` / `ci(android):` / `ci:` — CLI 或 CI
- `fix(server):` — 中继修复
- `docs:` — 仅文档

适用时请写 `Closes #NN` 关联 issue。

## 更多文档

- [docs/README.zh-CN.md](docs/README.zh-CN.md) — 双语文档索引
- [docs/SECURITY.zh-CN.md](docs/SECURITY.zh-CN.md) — 安全与备份
- [README.zh-CN.md](README.zh-CN.md) — 编译说明
