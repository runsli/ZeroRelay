# Security

**Languages:** English | [简体中文](SECURITY.zh-CN.md)

## Threat model (summary)

- **Relay:** Sees `routeHash`, ciphertext, sizes and timing, `senderId` / public keys; does **not** see E2EE plaintext.
- **Clients:** Hold `roomToken` (local HMAC), session keys, and identity private keys; protect against device loss and malicious relays (TLS pin, safety-number verification).

## Transport layer

| Capability | Notes |
|------------|--------|
| Preferred path | `POST /t` + `wss`, auth via `routeHash` |
| Legacy HTTP | Off by default (`ENABLE_LEGACY_HTTP=0`); `Deprecation` / `Sunset` when enabled |
| Android Release | Public HTTPS requires TLS certificate pin |
| Retention | Default 2h TTL, 100 messages per room |

## Identity and verification

- Verify **safety numbers** and mark contacts verified before sending in direct chats (Android / CLI).
- Warn when creating groups with unverified members.
- CLI identity and TLS pin files: mode `0600`, directory `0700`.

## Notification privacy

- System notifications show only **“You have a new message”** — no sender label or message body.

## Android account backup (migration)

- In the app: **Settings → Migration guide** walks through export, secure transfer, import, connection test, and safety-number re-check.
- **Settings → Export / import account backup** writes `zero-relay-account-backup-v1` (passphrase-encrypted file).
- Payload includes identity key pair, contacts, groups, ratchet state (same inner JSON as ratchet-only backup), relay server URL, and TLS SPKI pins.
- **Migrate to a new device:** install ZeroRelay → import backup → open Settings and **test connection**; if the relay certificate rotated, confirm the new TLS pin when prompted.
- Prefer **file export** over clipboard; ratchet-only backup remains under *Advanced* for partial restore.
- Local chat message history is **not** included in account backup (restore chats separately or rely on new messages after reconnect).

## Supply chain

```bash
# Local (same as CI)
./scripts/security-audit.sh
```

- **Dependabot:** weekly npm scans for repo root and `server/` (`.github/dependabot.yml`).
- **CI:** `security-audit.yml` runs `npm audit --audit-level=high` on PRs and weekly.
- **Android:** review `./gradlew :app:dependencies` before release; signing keys `*.jks`, `keystore.properties` stay local (gitignored).

## Reporting issues

Contact maintainers privately. Do **not** paste tokens, KV namespace ids, or key material in public issues.
