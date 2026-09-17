# Threat model (skeleton)

Complete this document before enabling mainnet. Assets: seed, Bark DB, encrypted backups, in-progress exit state, on-device fiat cache (not an asset of funds).

| Threat | Mitigation (intent) |
| --- | --- |
| Device theft | Keystore/Keychain, optional app lock, biometric spend confirm, OS file protection |
| Malware / accessibility overlays | Confirm screens with human-readable destination; prefer QR; no seed on clipboard |
| Compromised Ark server | Unilateral exit; refresh arkoor; do not trust server for balance truth without local DB |
| Server unavailable | Honest errors; exit path; seed+DB restore without server |
| Malicious invoice / address | Parse+network check; show type and amount; BIP21/321 confusion checks |
| Clipboard substitution | Destination summary; QR encouraged |
| Backup theft | Encrypted to seed-derived key; stealing backup without seed is useless |
| Backup rollback | Monotonic generation; refuse older snapshot over newer live state |
| DB corruption | Fail closed; do not auto-recreate a wallet on the same seed without restore UX |
| Interrupted transitions | Rely on Bark checkpointed actions; persist Keel operation IDs; reconcile on open |
| Dependency compromise | Pin Bark; lockfiles; no auto-merge of Bark/crypto/network deps |
| Build compromise | Verifiable Android builds as far as practical; no production keys in CI |
| Phishing | Signet/mainnet marker; no in-app accounts to phish |
| Lost seed and backup | Unrecoverable; UX must say so before the user proceeds |

Telemetry: none by default. Crash reporting, if ever added, is opt-in and redacted.
