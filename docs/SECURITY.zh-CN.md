# 安全说明

**文档语言：** [English](SECURITY.md) | 简体中文

## 威胁模型（简要）

- **中继**：可见 `routeHash`、密文、包长与时间、`senderId` / 公钥；不可见 E2EE 明文。
- **客户端**：持有 `roomToken`（本地 HMAC）、会话密钥与身份私钥；须防设备丢失与恶意中继（TLS pin、安全码验证）。

## 连接层

| 能力 | 说明 |
|------|------|
| 推荐路径 | `POST /t` + `wss`，鉴权 `routeHash` |
| Legacy HTTP | 默认关（`ENABLE_LEGACY_HTTP=0`）；开启时带 `Deprecation` / `Sunset` 头 |
| Android Release | 公网 HTTPS 须 TLS 证书 pin |
| 保留 | 默认 2h TTL、每房 100 条 |

## 身份与验证

- 单聊发送前须 **核对安全码** 并标记联系人已验证（Android / CLI）。
- 群聊创建时警告未验证成员。
- CLI 身份与 TLS pin 文件权限 `0600`，目录 `0700`。

## 通知隐私

- 系统通知仅显示 **「您有新消息」**，不含发送者备注或消息正文。

## 供应链

```bash
# 本地（与 CI 相同）
./scripts/security-audit.sh
```

- **Dependabot**：每周扫描根目录与 `server/` 的 npm 依赖（见 `.github/dependabot.yml`）。
- **CI**：`security-audit.yml` 在 PR / 每周一对 `npm audit --audit-level=high`。
- **Android**：发布前建议 `./gradlew :app:dependencies` 审阅；签名密钥 `*.jks`、`keystore.properties` 仅本地，已 gitignore。

## 报告问题

请通过私有渠道联系维护者，勿在公开 issue 中粘贴 token、KV id 或密钥材料。
