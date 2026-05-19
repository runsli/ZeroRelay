# F-Droid packaging notes

## Dependency policy

- QR scanning uses **ZXing** + **CameraX** only (`QrCodeAnalyzer.kt`). **No Google ML Kit.**
- Run `bash scripts/verify-fdroid-deps.sh` before submitting.

## Reproducible version numbers

Release versions are pinned in **`android/version.properties`** (committed):

```properties
versionName=1.0.0
versionCode=10000
```

Bump before each release:

```bash
scripts/android-version-from-tag.sh v1.0.1 --write
git add android/version.properties
git commit -m "chore(android): bump version to 1.0.1"
git tag v1.0.1
```

`app/build.gradle.kts` reads `version.properties` first, then `-P` / env (`VERSION_NAME`, `VERSION_CODE`), then an exact git tag on `HEAD`.

F-Droid can override via metadata `gradleprops`:

```yaml
gradleprops:
  - versionCode=10001
  - versionName=1.0.1
```

## Example metadata snippet

Submit to [fdroiddata](https://gitlab.com/fdroid/fdroiddata) (adjust `repo` URL):

```yaml
Categories:
  - Internet
License: MIT
SourceCode: https://github.com/YOUR_USER/YOUR_REPO
IssueTracker: https://github.com/YOUR_USER/YOUR_REPO/issues

Summary:
  E2EE chat client for your own relay

Description: |
  ZeroRelay is a client for a self-hosted or public relay. Messages are encrypted
  on device; the server forwards ciphertext only. You configure the relay URL
  in settings.

Builds:
  - versionName: '1.0.0'
    versionCode: 10000
    commit: v1.0.0
    subdir: android
    gradle:
      - assembleRelease
```

Anti-features: consider `NoSourceSince` none; if you ship a default public relay URL in future, document `NonFreeNet`.
