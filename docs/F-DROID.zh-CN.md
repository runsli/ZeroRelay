# F-Droid 打包说明

**语言：** [English](F-DROID.md) | 简体中文

## 依赖策略

- 扫码仅使用 **ZXing** + **CameraX**（`QrCodeAnalyzer.kt`），**不含 Google ML Kit**。
- 提交前运行：`bash scripts/verify-fdroid-deps.sh`

## 可复现的版本号

发版版本固定在 **`android/version.properties`**（需提交进 Git）：

```properties
versionName=1.0.0
versionCode=10000
```

每次发版前：

```bash
scripts/android-version-from-tag.sh v1.0.1 --write
git add android/version.properties
git commit -m "chore(android): bump version to 1.0.1"
git tag v1.0.1
```

`app/build.gradle.kts` 读取顺序：`version.properties` → `-P` / 环境变量 → `HEAD` 上精确 tag。

F-Droid 可通过元数据 `gradleprops` 覆盖：

```yaml
gradleprops:
  - versionCode=10001
  - versionName=1.0.1
```

## 元数据示例

提交到 [fdroiddata](https://gitlab.com/fdroid/fdroiddata)（按需修改 `repo` 地址）：

```yaml
Categories:
  - Internet
License: MIT
SourceCode: https://github.com/runsli/ZeroRelay
IssueTracker: https://github.com/runsli/ZeroRelay/issues

Summary:
  面向自建中继的 E2EE 聊天客户端

Description: |
  ZeroRelay 是用于自建或公共中继的客户端。消息在设备端加密，
  服务器仅转发密文。在设置中配置中继 URL。

Builds:
  - versionName: '1.0.0'
    versionCode: 10000
    commit: v1.0.0
    subdir: android
    gradle:
      - assembleRelease
```

反特性（Anti-features）：若无缺失源码可不用 `NoSourceSince`；若未来内置默认公共中继 URL，需注明 `NonFreeNet`。
