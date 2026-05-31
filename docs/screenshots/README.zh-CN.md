# README 用截图

**语言：** [English](README.md) | 简体中文

将 PNG 或 WebP 放在此目录（建议宽度 **≤1080px**，压缩后单张 **约 500 KB 以内**）。

## 建议文件名

| 文件 | 拍摄内容 |
|------|----------|
| `android-home.png` | 首页（联系人 / 导航） |
| `android-chat.png` | 聊天界面（几条示例消息） |
| `android-settings.png` | 设置页（中继地址可打码） |
| `cli-menu.png` | CLI 主菜单或 `zerorelay help` |

README 最少需要：**每个客户端各一张**（`android-chat.png`、`cli-menu.png`）。

## 注意

- 公开仓库请打码真实手机号、中继域名、安全码。
- 可用 `pngquant` 等工具压缩体积。
- 勿上传未获同意的他人照片。

添加文件后，根目录 [README.zh-CN.md](../../README.zh-CN.md) 会自动显示（与英文 README 共用同一批图片）。

## 当前文件（仓库内）

| 文件 | 来源 |
|------|------|
| `cli-menu.png` | 根据 `node cli.js help` 输出绘制 |
| `android-chat.png` | 占位示意图 — 连接真机后可用 `adb exec-out screencap -p` 替换 |
