# Upstream dependencies

Recorded 2026-09-03 from current Second/GitLab artifacts. Second’s Kotlin/Swift *getting started* pages still showed `0.9.0+bark.0.2.4`; **published packages are far newer**. Keel pins the artifacts, not the stale docs.

## Bark

| Item | Pin | Source |
| --- | --- | --- |
| Bark core | `bark-0.6.2` (`09e5b81d220d`) | https://gitlab.com/ark-bitcoin/bark |
| FFI bindings | `v0.22.0+bark-0.6.2` | https://gitlab.com/ark-bitcoin/bark-ffi-bindings |
| Android | `tech.second.bark:bark-android:0.22.0+bark-0.6.2` | GitLab Maven project `78057981` |
| JVM (tests / tools) | `tech.second.bark:bark-jvm:0.22.0+bark-0.6.2` | same |
| Swift | exact `0.22.0+bark-0.6.2` | https://gitlab.com/ark-bitcoin/bark-ffi-bindings.git |
| Bark license | MIT | upstream LICENSE |
| Bindings license | CC0-1.0 | upstream |

Maven registry:

`https://gitlab.com/api/v4/projects/78057981/packages/maven`

Android also needs `net.java.dev.jna:jna:5.18.1@aar` (exclude the transitive desktop JNA jar) and `jniLibs.useLegacyPackaging = true`.

## Signet

| Role | URL |
| --- | --- |
| Ark server | `https://ark.signet.2nd.dev` |
| Esplora | `https://esplora.signet.2nd.dev` |
| Faucet | `https://signet.2nd.dev/` |

Docs index: https://second.tech/docs/llms.txt

## Protocol notes that affect Keel

- Bark 0.6.x servers refuse refresh and Lightning from clients older than 0.6.0.
- Seed-only restore recovers **balance with server mailbox cooperation**, not history or in-progress exits ([backups](https://second.tech/docs/backups.md), Bark 0.5.0 mailbox).
- There is **no** official Bark SDK snapshot API. Continuous backup is Keel’s responsibility. `bark-backupd` uses SQLite `VACUUM INTO` for a consistent live copy.
- Crash-safe sends, Lightning receives, boards, and offboards exist in Bark 0.5+ as checkpointed wallet actions. Keel must not mark a payment failed solely because the UI timed out.

## Examples reviewed (architecture only)

| Project | Takeaway for Keel |
| --- | --- |
| Official Kotlin/Swift examples | UniFFI `Wallet.create` / `open`, Signet `Config`, no mnemonic logging in production |
| Noah (React Native + account/S3) | Backup **after every state change**; do **not** copy the account backend |
| bark-backupd | `VACUUM INTO`, generation numbers, rollback/staleness guards |
| Arké (native Swift) | Native iOS patterns; not a UI template |

## Known doc/code mismatches

- Getting-started snippets print the mnemonic. Keel must never do that.
- Kotlin `Config` field lists differ between the website and the 0.22.0 README. The compiled 0.22.0 binding is authoritative.
- Dart `Wallet` surface is a useful method catalog; Kotlin/Swift names are confirmed at compile time against `0.22.0+bark-0.6.2`.
