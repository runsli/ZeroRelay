# ZeroRelay - 端到端加密聊天应用

**文档语言：** [English](README.md) | 简体中文

跨平台安全聊天：**Android 客户端**使用 **Jetpack Compose + Material 3**；后端为 Node.js / Cloudflare Workers。消息在客户端 AES-256-GCM 加密，服务器仅转发密文。

## 架构

```
ZeroRelay/
├── android/          # Jetpack Compose 客户端 (Kotlin)
├── server/           # Node.js / TypeScript 后端
├── web/              # 浏览器客户端 (Vite + TypeScript)
└── cli.js            # 命令行客户端 (Node.js)
```

| 模块 | 技术栈 |
|------|--------|
| **Android** | Kotlin, Jetpack Compose, Material 3, OkHttp (WebSocket 收 + HTTP 发/轮询) |
| **Web** | TypeScript, Vite, Web Crypto, IndexedDB |
| **Server** | Node.js, Hono, Cloudflare Workers |
| **CLI** | Node.js |

> 旧版 Flutter 客户端已移除；Web 端为新的 TypeScript 实现（见 `web/`）。Material 3 在 Android 上由 [Compose Material3](https://developer.android.com/jetpack/androidx/releases/compose-material3) 实现，主题种子色 `#0F9D47` 见 `android/app/src/main/kotlin/app/zerorelay/ui/theme/Theme.kt`。

## 核心特性

- **端到端加密**：私聊 X25519 + HKDF 会话密钥，消息棘轮 v2（AES-256-GCM）；群聊共享群密钥 + 版本化信封
- **中继安全**：房间 HMAC 令牌（客户端本地推导 `routeHash`）、Ed25519 消息签名、速率限制与体积上限；CLI 支持 TLS TOFU pin 与可选口令加密身份
- **零知识**：服务器只转发密文，不解密
- **实时通信**：Android WebSocket 收消息 + HTTP 长轮询/发送；CLI 使用 HTTP
- **Material Design 3**：`MaterialExpressiveTheme`、Expressive 动效；设置中可开关 **Material You 动态配色**（Android 12+）
- **自适应布局**：≥600dp 显示 `NavigationRail`；≥840dp 首页与聊天双栏（list-detail）

## 快速开始

### 后端

```bash
cd server
npm install
npm start
# http://localhost:8787
```

### Android

**安装（推荐）**：从本仓库 **GitHub Releases** 下载最新 **Release APK**（由项目维护者本地编译并签名后发布）。

**自行编译**：若需 fork、改代码或用自己的密钥更新，见下文 [Android Release 包构建](#四android-release-包构建)。

**编译环境要求**：**JDK 26**（推荐，Gradle 9.4 已支持）或 JDK 17+、**Android SDK 37**（`compileSdk` / `targetSdk`）、Android Studio

```bash
# 使用 JDK 26 构建（示例）
export JAVA_HOME=$(/usr/libexec/java_home -v 26)
cd android
./gradlew assembleDebug
# 或在 Android Studio 打开 android/ 目录，Run 'app'
```

构建链说明：

| 层级 | 版本 | 作用 |
|------|------|------|
| 运行 Gradle | **JDK 26** | 执行 `./gradlew` |
| 编译工具链 | JDK 26 | `java.toolchain` |
| 应用字节码 | **JVM 17** | 运行在 Android 设备上（`jvmTarget = 17`） |

在 `android/local.properties` 中可设置 `org.gradle.java.home` 指向 JDK 26，参见 `local.properties.example`。

| 环境 | 服务器地址 |
|------|------------|
| Android 模拟器 | `http://10.0.2.2:8787`（应用内默认） |
| Android 真机 | `http://<电脑局域网IP>:8787` |

Debug 构建允许 HTTP；Release 请使用 HTTPS。

### Web 网页端

浏览器界面（MVP），见 [web/README.md](web/README.md)。

```bash
cd web && npm install && npm run dev
# 或在仓库根目录: npm run dev:web
```

**一键部署（推荐）：** 使用 `server/` 上的 Deploy 按钮，网页与中继同域。**可选：** 单独部署 [Cloudflare Pages](web/README.md#deploy-to-cloudflare-pages-open-and-use)（仅 `web/`）。

### CLI

**新电脑一键安装：**

```bash
git clone <仓库地址> && cd chat
./scripts/cli-setup.sh          # 安装依赖，并可选注册全局命令 zerorelay / zr
zerorelay help
zerorelay config set server https://relay.example.com
zerorelay                          # 进入交互主菜单
```

不想全局安装：`./zerorelay help`，或 `npm run setup:cli -- --no-link` 后把仓库目录加入 `PATH`。

```bash
node cli.js help                  # 与 zerorelay 相同
# 可选：加密 ~/.zero-relay/identity.enc.json
export ZERO_RELAY_PASSPHRASE='你的口令'
```

`~/.zero-relay/` 下身份、配置、TLS pin 等文件写入为 `0600`，目录 `0700`。

聊天会话使用 **WebSocket 收消息 + HTTP 长轮询备用**（与 Android 一致）。协议常量见 [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md)。

常用：`contact list` / `contact verify <id>`、`config show`（含最近联系人）、`config test`、`group create`、`ratchet export --clipboard`（与 App 备份互通）。

调试：`CRYPTO_LOG=1 node cli.js chat <id>`（对齐 Android Debug 包 `ZeroRelay.Crypto` 日志）。

**P3：** 聊天中 `/detach` 可后台收消息（终端响铃）；`node cli.js qr --png ./qr.png` 导出二维码；`npm run build:cli` 打包单文件 CLI（需安装 dev 依赖 `pkg`）。

**互操作测试**（需先 `cd server && npm start`）：

```bash
npm run test:interop
```

## Android 项目结构

```
android/app/src/main/kotlin/app/zerorelay/
├── MainActivity.kt
├── ui/
│   ├── AppRoot.kt           # 导航 + 全局 Snackbar
│   ├── home/HomeScreen.kt   # 联系人/群聊；宽屏 NavigationRail
│   ├── chat/ChatScreen.kt
│   ├── snackbar/AppSnackbarBus.kt
│   └── theme/Theme.kt       # MaterialExpressiveTheme、品牌色 #0F9D47
└── data/
    ├── crypto/              # IdentityCrypto, MessageCipher, MessageRatchet
    ├── chat/ChatRepository.kt
    ├── identity/IdentityStore.kt
    └── network/ServerUrl.kt
```

## 使用

1. 启动 `server`（本地或 Cloudflare Workers）
2. Android：设置服务器地址 → 生成身份 / 扫码添加联系人 → 单聊或建群
3. CLI：`node cli.js <服务器URL>` → `contact add` / `group create` → `chat`

## 部署

生产环境推荐：**Cloudflare Workers + KV** 作为中继，客户端通过 **HTTPS / WSS** 连接（`POST /t` 全隧道 + `/ws`）。协议细节见 [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md)，上线前安全清单见 [docs/SECURITY.zh-CN.md](docs/SECURITY.zh-CN.md)。

### 部署总览

```
┌─────────────┐     HTTPS POST /t      ┌──────────────────────────┐
│ Android /   │ ──────────────────────►│ Cloudflare Workers       │
│ CLI         │     WSS /ws            │ + KV (密文缓存)          │
└─────────────┘                        └──────────────────────────┘
       │                                         │
       └─ E2EE 明文仅在本机解密 ─────────────────┘ 中继只见 routeHash + 密文
```

| 阶段 | 目标 | 主要命令 |
|------|------|----------|
| 1. 本地联调 | 开发 / 互操作测试 | `cd server && npm start` |
| 2. 预发验证 | 与线上一致的 Workers 运行时 | `cd server && npm run dev` |
| 3. 生产上线 | 公网中继 | `npm run check && npm run deploy` |
| 4. 客户端 | 指向 HTTPS 域名并 pin 证书 | App 设置 / `cli.js config` |

### 一键部署到 Cloudflare（中继 + 网页，推荐）

将本仓库推送到 **GitHub 公开仓库** 后，在 **`server/`** 目录使用官方 [Deploy to Cloudflare](https://developers.cloudflare.com/workers/platform/deploy-buttons/) 按钮：登录 Cloudflare → 授权 Git → 自动创建 Worker、**KV**、编译网页并部署（**同一 HTTPS 地址** 同时提供 API 与 Web 聊天界面）。

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/runsli/ZeroRelay/tree/main/server)

部署完成后，用浏览器打开 `https://<你的-worker>.workers.dev` 即可使用（无需再建 Pages）。健康检查：`curl https://<你的-worker>.workers.dev/health`。

> **不要用 Pages 一键部署 `web/`** — Cloudflare 官方 Deploy 按钮不支持纯 Pages 项目；本仓库把网页打进 Worker（`server/public`）。  
> 若你 fork 了仓库，可用 `./scripts/deploy-button-url.sh` 生成指向你 fork 的按钮链接。

**按钮部署流程概要：**

1. 点击按钮 → 用 Cloudflare 账号登录并授权 GitHub。
2. 在配置页确认 Worker 名称、仓库 fork 名称（可改）。
3. 平台按 `server/wrangler.toml` **自动创建 `MESSAGE_KV`** 并绑定，无需手填 KV id。
4. 构建完成后获得 `https://<worker>.<account>.workers.dev`，可用 `curl` 访问 `/` 自检。
5. （可选）在 Workers 控制台绑定自定义域名；Android / CLI / Web 填入 **HTTPS** 地址并完成 TLS pin（Web 使用浏览器证书信任，无 CLI 式 pin）。
6. 用浏览器打开 Worker 地址即可使用 **Web 聊天**（已随 `server/` 一键部署打包；无需单独 Pages，除非你要自定义域名拆分到 Pages）。

**要求：** 仓库公开；`server/` 目录自包含（含 `package.json`、`wrangler.toml`、`src/`）。不支持仅私有仓（除非自行用 Wrangler CLI 部署）。

**与 CLI 部署的区别：** 一键部署走 Workers Builds 并写入 fork 后仓库中的 KV id；本地 `wrangler deploy` 需自行 `wrangler kv namespace create` 并把 id 写入 `wrangler.toml`（参见下文「步骤 3」）。

---

### 一、本地开发部署（中继）

用于本机调试 Android 模拟器、真机局域网与 CLI。

1. **安装依赖**

```bash
cd server
npm install
```

2. **启动本地 Node 中继**（`server-local.js`，默认 `8787`）

```bash
npm start
# 或带传输日志：npm run start:log
```

3. **可选环境变量**（项目根目录 `.env` 或 shell 导出，参见 `.env.example`）

| 变量 | 说明 |
|------|------|
| `PORT` | 监听端口，默认 `8787` |
| `MESSAGE_TTL_SEC` / `MAX_MESSAGES_PER_ROOM` | 与 Workers 相同语义 |
| `ENABLE_LEGACY_HTTP` | 本地调试 legacy JSON 时设为 `1` |
| `TRANSPORT_LOG` | `1` 打印 HTTP/WS 传输日志 |
| `RELAY_LOG` | `1` 打印服务端错误 |

4. **验证**

```bash
curl -s http://127.0.0.1:8787/
# 项目根目录
npm run test:interop
```

客户端地址：模拟器 `http://10.0.2.2:8787`；真机 `http://<电脑局域网 IP>:8787`（须同一 Wi‑Fi）。

---

### 二、Cloudflare Workers 生产部署

#### 前置条件

- [Cloudflare](https://dash.cloudflare.com) 账号
- Node.js **≥ 22**（Wrangler 4；建议 22 LTS）
- 已安装 Wrangler CLI（`server` 目录 `npm install` 会带上 `wrangler`）

#### 步骤 1：登录 Wrangler

```bash
cd server
npx wrangler login
```

#### 步骤 2：创建 KV 命名空间

在 [Cloudflare Dashboard](https://dash.cloudflare.com) → **Workers & Pages** → **KV** → **Create namespace**，名称例如 `zero-relay-messages`。

记下 **Namespace ID**（仅保存在本地 `wrangler.toml`，勿提交 Git）。

也可用 CLI：

```bash
npx wrangler kv namespace create MESSAGE_KV
# 输出中的 id 填入 wrangler.toml
```

#### 步骤 3：配置 `wrangler.toml`

仓库已包含 [`server/wrangler.toml`](server/wrangler.toml)（供一键部署自动填 KV id）。**CLI 手动部署**时创建命名空间并写入 id：

```bash
cd server
npx wrangler kv namespace create MESSAGE_KV
# 将输出的 id 写入 wrangler.toml：
# [[kv_namespaces]]
# binding = "MESSAGE_KV"
# id = "<上一步的 id>"
```

也可参考 [`server/wrangler.toml.example`](server/wrangler.toml.example)。`[vars]` 默认可不改。

> **勿提交** `.dev.vars`、`server/.wrangler/`、含真实密码的 `wrangler.local.toml`（已在 `.gitignore`）。

#### 步骤 4：类型检查与依赖审计（建议）

```bash
npm run check
npm run audit
# 或项目根目录：npm run audit:all
```

#### 步骤 5：本地 Workers 模拟（可选）

在绑定真实 KV 前，可用 Miniflare 联调：

```bash
npm run dev
# 默认 http://127.0.0.1:8787
```

#### 步骤 6：部署到 Cloudflare

```bash
npm run deploy
```

成功后终端会输出 Workers 地址，例如：

- `https://zero-relay-server.<你的子域>.workers.dev`

#### 步骤 7：绑定自定义域名（推荐生产）

1. Dashboard → **Workers & Pages** → 你的 Worker → **Settings** → **Domains & Routes**
2. 添加自定义域，例如 `relay.example.com`
3. 确保 DNS 已代理到 Cloudflare（橙色云）
4. 客户端统一使用 **`https://relay.example.com`**（自动支持 **WSS**）

#### 步骤 8：部署后自检

```bash
curl -s https://relay.example.com/
# 期望：{"status":"ok","service":"ZeroRelay Server",...}
```

在项目根目录将 CLI / 互操作测试指向线上（可选）：

```bash
ZERO_RELAY_SERVER=https://relay.example.com npm run test:interop
```

#### Workers 环境变量说明

在 `wrangler.toml` 的 `[vars]` 中配置（非密钥类配置）：

| 变量 | 默认 | 说明 |
|------|------|------|
| `MESSAGE_TTL_SEC` | `7200`（2 小时） | KV 单条消息 TTL（秒） |
| `MAX_MESSAGES_PER_ROOM` | `100` | 每房间最多保留条数 |
| `ENABLE_LEGACY_HTTP` | `0` | `1` 才启用 `/send`、`POST /messages`（带 Deprecation 头） |
| `RELAY_LOG` | `0` | `1` 时在 Workers 打错误日志 |
| `ENVIRONMENT` | `production` | 标识环境，便于日志区分 |

**密钥类**配置请用 `wrangler secret put <NAME>`，不要写入 `wrangler.toml`。

修改 `[vars]` 后需重新执行 `npm run deploy` 生效。

#### 更新与回滚

```bash
cd server
npm run check
npm run deploy
```

在 Cloudflare Dashboard → Worker → **Deployments** 可查看历史版本并回滚。

---

### 三、客户端接入生产中继

#### Android（Release）

1. 使用 **HTTPS** 地址（Release 构建禁止明文 HTTP，见 `network_security_config`）。
2. 应用内 **设置 → 服务器地址** 填 `https://relay.example.com`。
3. 点击 **测试连接**；若证书变更，按提示 **信任并保存 TLS 指纹**（Release 公网中继必须先 pin）。
4. 添加联系人后 **核对安全码** 再发消息（Release 无法跳过验证）。

Debug 包仍可用 `http://10.0.2.2:8787` 连本地中继。

#### CLI

```bash
node cli.js config set server https://relay.example.com
node cli.js config test
# 若提示 TLS pin 不匹配：
node cli.js config trust-pin
node cli.js contact list
```

身份目录默认 `~/.zero-relay/`（权限 `0700`/`0600`）。可选：

```bash
export ZERO_RELAY_PASSPHRASE='你的口令'
```

#### 群邀请中的中继地址

生成群二维码时，若当前设置的服务器为 HTTPS 公网地址，邀请 payload 会带上 `serverUrl`，他人扫码后可自动填入同一中继。

---

### 四、Android Release 包构建

#### 分发方式说明

| 角色 | 做法 |
|------|------|
| **普通用户** | 从 **GitHub Releases** 安装维护者提供的 **已编译 APK**（随版本标签发布）。 |
| **开发者 / fork** | 克隆仓库，用 **自己的** 签名密钥执行 `assembleRelease`，得到可自行分发、自行更新的 APK。 |

签名不同则 Android 视为不同应用：自编译包**不能**直接覆盖安装官方 Release APK（需先卸载，除非你使用同一套密钥）。

#### 签名文件（不进 Git）

发布用密钥库与密码只保存在**编译者本机**，开源仓库不包含：

| 文件 | 是否提交 Git |
|------|----------------|
| `android/keystore.properties.example` | 是（模板） |
| `android/keystore.properties` | **否**（已 gitignore） |
| `*.jks` / `*.keystore` | **否**（已 gitignore） |

CI 运行 `scripts/verify-no-signing-in-repo.sh`，防止误提交签名材料。

#### 版本号（可复现 / 由 tag 驱动）

固定在 **`android/version.properties`**（需提交进 Git）。发版前执行：

```bash
scripts/android-version-from-tag.sh v1.0.1 --write
git add android/version.properties && git commit -m "chore(android): bump version to 1.0.1"
```

Gradle 读取顺序：`version.properties` → `-P` / 环境变量 → `HEAD` 上精确 tag → 默认 `1.0.0` / `10000`。F-Droid 说明见 [docs/F-DROID.md](docs/F-DROID.md)。

#### 维护者：一键本地发版

**要求**：JDK 26（或 17+）、Android SDK 37、`android/local.properties` 中配置 `sdk.dir`（参见 `android/local.properties.example`）。

一次性配置：`cp android/keystore.properties.example android/keystore.properties`，并将 `.jks` 放在 `android/` 下。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 26)   # 按需
scripts/android-release-build.sh v1.0.1
```

产出：仓库根目录 `zerorelay-v1.0.1.apk`，应用内版本与 tag 一致。然后：

```bash
git tag v1.0.1 && git push origin v1.0.1
# 将 zerorelay-v1.0.1.apk 上传到 GitHub Releases 的 v1.0.1
```

手动 Gradle（相同版本号）：`VERSION_NAME=1.0.1 VERSION_CODE=10001 ./gradlew -p android :app:assembleRelease`

发布前建议：`./gradlew :app:dependencies` 审阅依赖；真机安装 Release 包连生产中继完成一次单聊 + 群聊冒烟。

#### 自行编译 Release APK

步骤相同，但使用 **你自己的** `keystore.properties` 与 `.jks`。首次生成密钥库示例：

```bash
keytool -genkey -v -keystore android/zerorelay-release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias zerorelay
```

配置 `android/keystore.properties` 后执行 `assembleRelease`。仅当设备上已安装的包与**同一密钥**签名时，你的 APK 才能作为「更新」覆盖安装。

#### CI（可选，非官方安装包）

[`.github/workflows/android-release.yml`](.github/workflows/android-release.yml) 在推送到 `main` / 标签时做 **CI 编译检查**（debug 签名，构件名含 `ci-debug`）。推送 `v*` 标签时 CI 与 `android-release-build.sh` 使用相同版本号；**不会**代替正式包 — 请用本地脚本编译后上传 APK 到 **GitHub Releases**。

---

### 五、上线前检查清单

- [ ] `ENABLE_LEGACY_HTTP=0`，客户端仅走 `POST /t` + `wss`
- [ ] `RELAY_LOG=0`，`wrangler.toml` / KV id 未进入 Git；Android 签名 `*.jks`、`keystore.properties` 仅本地
- [ ] 自定义域名为 **HTTPS**，证书有效
- [ ] `npm run audit:all` 无 high/critical
- [ ] `npm run test:interop` 对生产 URL 通过（或先在 staging Worker 验证）
- [ ] Android Release 已对生产域 **TLS pin**；CLI 已 `config test`
- [ ] 已向用户说明：中继可见元数据（`routeHash`、时间、包长），明文仅 E2EE 保护

连接层隐私（v2）：鉴权使用 `routeHash`，不上传 `roomToken` 或聊天 `roomId`；KV 默认保留 2h / 每房 100 条。

## 安全

详见 [docs/SECURITY.zh-CN.md](docs/SECURITY.zh-CN.md)。本地依赖审计：`npm run audit:all`。

## License

MIT
