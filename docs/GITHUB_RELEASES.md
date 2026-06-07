# Automated Android GitHub Releases

**Languages:** English | [简体中文](GITHUB_RELEASES.zh-CN.md)

Push a semver tag `vX.Y.Z` to build a **Release-signed APK** and attach it to a **GitHub Release**.

Workflow: [`.github/workflows/android-github-release.yml`](../.github/workflows/android-github-release.yml)

## One-time setup (GitHub Actions secrets)

In **Settings → Secrets and variables → Actions → New repository secret**, add:

| Secret | Value |
|--------|--------|
| `ANDROID_KEYSTORE_BASE64` | Base64 of your `.jks` (see below) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias (e.g. `zerorelay`) |
| `ANDROID_KEY_PASSWORD` | Key password |

**Encode keystore (macOS):**

```bash
base64 -i android/zerorelay-release.jks | pbcopy
# paste into ANDROID_KEYSTORE_BASE64
```

**Linux:**

```bash
base64 -w0 android/zerorelay-release.jks
```

Never commit `.jks` or `keystore.properties` to Git.

**If release fails with `base64: invalid input`:** the secret is malformed. Regenerate on a machine that has your `.jks` (single line, no quotes):

```bash
./scripts/print-github-keystore-secret.sh /path/to/zerorelay-release.jks --raw > /tmp/ks.b64
./scripts/verify-keystore-base64.sh /tmp/ks.b64   # should print ok
```

Update **Settings → Secrets → ANDROID_KEYSTORE_BASE64**, then **Actions → Android GitHub Release → Run workflow** (tag `v1.0.0`).

## Publish a version

1. Bump committed version:

   ```bash
   scripts/android-version-from-tag.sh v1.0.1 --write
   git add android/version.properties
   git commit -m "chore(android): bump version to 1.0.1"
   git push origin main
   ```

2. Tag and push:

   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```

3. Wait for **Android GitHub Release** workflow → open **Releases** → download `zerorelay-v1.0.1.apk`.

The workflow checks that `android/version.properties` matches the tag before building.

## Local build (optional)

Same APK as CI when secrets are not involved:

```bash
scripts/android-release-build.sh v1.0.1
```

## CI smoke build (not for users)

Push to `main` runs **Android build (CI)** and uploads a **debug-signed** `*-ci-debug.apk` artifact — do not publish that as an official release.
