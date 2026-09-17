# Known limitations

Honest status as of 0.5.0.

- **Experimental.** Not audited. Signet only (plus a LAN regtest flavor).
- **iOS** is not compiled on Linux (no Xcode). Source/CI land in a later phase.
- **PWA/web wallet** landed 2026-09-09 (`web/`, served at https://clarionlab.dev/keel): Bark WASM SDK (`@secondts/bark` 0.23.0) + IndexedDB, same feature set as Android. Browser-specific limits: the app lock is a passphrase (no biometrics/Keystore — the mnemonic is KEELBK01-encrypted in IndexedDB); full-DB backups (KEELDB01) are not portable across platforms (different storage engines) — seed backups (KEELBK01) are interchangeable; background sync only runs while the tab is open.
- **Backup destinations.** Continuous encrypted DB backup landed in 0.5.0 (Keystore-encrypted auto-snapshot after every mutation + passphrase-encrypted full export via SAF). Not yet wired: automatic SAF-folder sync and native Drive/iCloud destinations — the manual export covers them by hand.
- **App lock** (0.5.0) gates the UI at launch and after 1 min in background; the wallet stays open in memory while backgrounded. Screenshots are blocked on seed/backup screens only (0.5.3).
- **Background refresh/exit** cannot be guaranteed by Android or iOS. Mitigated in 0.5.0: every foreground sync auto-refreshes VTXOs nearing expiry.
- **Fiat** is a third-party price (CoinGecko), cached, timestamped, and never used to sign amounts. Rates can be stale or unavailable.
- **Official Second Kotlin/Swift snippets** were behind current packages (`0.9.0` vs `0.22.0`). We pin artifacts and treat mismatches as upstream doc bugs.
- **Name:** unrelated “Keel Money” exists. No trademark clearance is claimed.
