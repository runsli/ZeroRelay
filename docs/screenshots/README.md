# Screenshots for README

**Languages:** English | [简体中文](README.zh-CN.md)

Add PNG or WebP files here (recommended width **1080px** or less; keep each file **under ~500 KB** after compression).

## Suggested filenames

| File | What to capture |
|------|-----------------|
| `android-home.png` | Home screen (contact list / navigation) |
| `android-chat.png` | Open chat with a few messages |
| `android-settings.png` | Settings with relay URL (blur secrets if any) |
| `cli-menu.png` | CLI main menu or `zerorelay help` in terminal |

Minimum for README: **one image per client** (`android-chat.png`, `cli-menu.png`).

## Tips

- Use **light and dark** only if you want two rows; one theme is enough for README.
- Hide or blur real phone numbers, relay hostnames, and safety numbers if the repo is public.
- Compress: `pngquant --quality=80-95 android-chat.png` or export WebP from your editor.
- Do not commit photos of real users without consent.

After adding files, they appear automatically in the root [README.md](../../README.md) and [README.zh-CN.md](../../README.zh-CN.md).

## Current assets (in repo)

| File | Source |
|------|--------|
| `cli-menu.png` | Rendered from live `node cli.js help` output |
| `android-chat.png` | Placeholder product-style mock — replace with `adb exec-out screencap -p` when a device is connected |
