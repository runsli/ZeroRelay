## Summary

<!-- What changed and why? Link issues with "Closes #NN" in the commit or here. -->

## Test plan

<!-- Commands you ran and what you verified manually. -->

- [ ] Local tests (see checklist below)
- [ ] CI checks green on this PR

## Protocol / crypto

- [ ] No protocol, relay API, or crypto changes
- [ ] Updated [docs/PROTOCOL.md](../docs/PROTOCOL.md) and [docs/PROTOCOL.zh-CN.md](../docs/PROTOCOL.zh-CN.md)
- [ ] Updated [scripts/interop-test.js](../scripts/interop-test.js)
- [ ] Ran `npm run test:interop` (relay on `http://127.0.0.1:8787`)

## Android strings (i18n)

- [ ] No user-facing string changes
- [ ] Updated both `android/app/src/main/res/values/strings.xml` (EN) and `values-zh-rCN/strings.xml` (ZH)
- [ ] Ran `bash scripts/check-android-strings.sh`

## Documentation

- [ ] No documentation updates needed
- [ ] Updated relevant docs (English + Chinese where the repo maintains pairs)

## CI checks

These workflows run when matching paths change (see [CONTRIBUTING.md](../CONTRIBUTING.md)):

| Check | Typical trigger |
|-------|-------------------|
| **Android PR check** | `android/**` — string parity, `compileDebugKotlin`, `lintDebug` |
| **Server PR check** | `server/**` — `npm run check` (`tsc`) |
| **Interop test** | `cli*.js`, `server/**`, `scripts/interop-test.js`, `android/**/crypto/**` |
| **Security audit** | All PRs — `npm audit` on root and `server/` |

**Local equivalents**

| Area | Command |
|------|---------|
| Interop | `npm run test:interop` |
| Android Debug APK | `cd android && ./gradlew :app:assembleDebug` |
| Android strings | `bash scripts/check-android-strings.sh` |
| Server types | `cd server && npm run check` |
