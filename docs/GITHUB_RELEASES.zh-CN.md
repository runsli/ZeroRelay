# Android 自动发布到 GitHub Releases

**语言：** [English](GITHUB_RELEASES.md) | 简体中文

推送语义化标签 `vX.Y.Z` 后，自动构建 **Release 签名 APK** 并附加到 **GitHub Release**。

工作流：[`.github/workflows/android-github-release.yml`](../.github/workflows/android-github-release.yml)

## 一次性配置（GitHub Actions Secrets）

在 **Settings → Secrets and variables → Actions → New repository secret** 中添加：

| Secret 名称 | 填写内容 |
|-------------|----------|
| `ANDROID_KEYSTORE_BASE64` | `.jks` 的 Base64（见下方） |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名（如 `zerorelay`） |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |

**生成 Base64（macOS）：**

```bash
base64 -i android/zerorelay-release.jks | pbcopy
# 粘贴到 ANDROID_KEYSTORE_BASE64
```

或使用脚本：

```bash
./scripts/print-github-keystore-secret.sh android/zerorelay-release.jks
```

**Linux：**

```bash
base64 -w0 android/zerorelay-release.jks
```

切勿将 `.jks` 或 `keystore.properties` 提交到 Git。

**Release 失败 `base64: invalid input`：** Secret 内容无效。在存有 `.jks` 的电脑上重新生成并**整行**粘贴（不要加引号）：

```bash
./scripts/print-github-keystore-secret.sh /path/to/zerorelay-release.jks --raw > /tmp/ks.b64
./scripts/verify-keystore-base64.sh /tmp/ks.b64   # 应输出 ok
```

然后更新 **Settings → Secrets → ANDROID_KEYSTORE_BASE64**，再 **Actions → Android GitHub Release → Run workflow**（tag `v1.0.0`）。

## 发布新版本

1. 更新并提交版本号：

   ```bash
   scripts/android-version-from-tag.sh v1.0.1 --write
   git add android/version.properties
   git commit -m "chore(android): bump version to 1.0.1"
   git push origin main
   ```

2. 打标签并推送：

   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```

3. 等待 **Android GitHub Release** 工作流完成 → 打开 **Releases** → 下载 `zerorelay-v1.0.1.apk`。

工作流会校验 `android/version.properties` 与标签一致后再构建。

## 本地构建（可选）

未使用 Secrets 时，与 CI 产物相同：

```bash
scripts/android-release-build.sh v1.0.1
```

## CI 冒烟构建（勿发给用户）

推送到 `main` 会运行 **Android build (CI)**，上传 **debug 签名** 的 `*-ci-debug.apk` 构件 — 不要当作正式版发布。
