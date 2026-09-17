# Architecture

Keel is two independently releasable native apps over one unmodified Bark core.

```text
  Android (Kotlin/Compose)          iOS (Swift/SwiftUI)
           \                              /
            \   specification + fixtures /
             \                          /
              official Bark UniFFI bindings
                          |
                    Bark Rust core
                          |
              Ark server + Esplora (Signet)
```

No React Native, Flutter, KMP, WebView shell, or shared UI toolkit. Consistency is specified behavior plus parity tests.

## Process and data

1. The app generates or accepts a BIP39 mnemonic and stores it in platform secure storage (Android Keystore / iOS Keychain).
2. Bark’s wallet database lives only in the app sandbox (`noBackupFilesDir` on Android; Application Support with backup-exclusion on iOS).
3. All Ark/Lightning/on-chain protocol work goes through `uniffi.bark.Wallet` (Android) or `Bark.Wallet` (iOS).
4. After every successful mutating Bark call, Keel takes a consistent SQLite snapshot (`VACUUM INTO` or equivalent), encrypts it, and writes it to the user-chosen destination(s).
5. Fiat prices are fetched for display only. The signed amount is always integer sats.

## Android modules

| Module | Role |
| --- | --- |
| `:core` | JVM: satoshi types, payment-request parsing, state machines, backup envelope (no Android, no native Bark) |
| `:app` | Compose UI, Bark Android binding, Keystore, SAF / Drive export |

## iOS

Independent Xcode project. Same `specification/` machines. Compiles only under Xcode (local Mac or GitHub `macos-latest`).

## Network gate

`BuildConfig` / Swift compile flags expose `MAINNET_ENABLED=false`. Signet is the only selectable network until an explicit authorization and a documented gate flip.

## Identifiers

| Kind | Value |
| --- | --- |
| User-facing name | Keel |
| GitHub | `clarionchain/keel` |
| Application ID / bundle ID | `io.clarionchain.keel` |

Renaming the display name must not change derivation paths, Bark datadir layout, or backup format magic.
