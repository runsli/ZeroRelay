# 安全说明

**语言：** [English](SECURITY.md) | 简体中文

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

## Android 账户备份（换机迁移）

- 应用内：**设置 → 换机指南** 提供分步说明（导出、安全传输、导入、测试连接、核对安全码）。
- **设置 → 导出 / 导入账户备份** 生成 `zero-relay-account-backup-v1` 口令加密文件。
- 内容包括身份密钥对、联系人、群聊、棘轮状态（内层 JSON 与「仅棘轮备份」兼容）、中继服务器地址与 TLS SPKI 指纹。
- **迁移到新设备：** 安装 ZeroRelay → 导入备份 → 在设置中 **保存并测试连接**；若证书已轮换，按提示确认新的 TLS 指纹。
- 优先使用 **文件导出**，避免剪贴板；「高级：仅棘轮状态备份」用于部分恢复。
- 本地聊天消息历史 **不包含** 在账户备份中（换机后需重新收发或依赖本地消息库另行处理）。

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
