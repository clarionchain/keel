# ADR 0004: Continuous encrypted backups

## Status

Accepted

## Decision

Backup after every successful mutating Bark operation. Snapshot with SQLite `VACUUM INTO` (or online backup API). Encrypt with a key derived from the mnemonic via HKDF (`keel-backup-v1`) and AES-256-GCM. Include a versioned manifest and monotonic generation for rollback detection. Never upload the mnemonic.

Destinations: always manual file export/import; Android SAF; optional Google Drive; optional iCloud; other user-picked common locations. No account is required.

## Consequences

Device-loss recovery of history/exits requires an off-device encrypted copy plus the seed. Seed-only recovery remains documented as partial.
