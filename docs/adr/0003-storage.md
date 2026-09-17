# ADR 0003: Storage

## Status

Accepted

## Decision

- Mnemonic: platform secure storage (Android Keystore via EncryptedSharedPreferences; iOS Keychain).
- Bark datadir: app sandbox, excluded from unencrypted cloud device backup (`noBackupFilesDir` / backup-exclusion).
- Fail closed on corruption or schema mismatch. Never silently recreate a wallet.

## Consequences

OS cloud backup of the raw DB is not a recovery plan. Keel’s encrypted export is.
