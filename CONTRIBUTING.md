# Contributing to Keel

## Rules for money-moving code

- Do not reimplement Ark, Bitcoin signing, VTXO logic, Lightning bridging, or cryptographic primitives. Use official Bark bindings.
- Do not fork Bark to ship Keel. Prefer an upstream fix.
- Never log, print, or commit seeds, keys, wallet databases, decrypted backups, or payment preimages.
- Amounts are integer satoshis only. Fiat is display-only and must never be the signed amount.
- Do not enable mainnet without an explicit project decision recorded in docs.
- Do not add analytics or crash reporting by default.

## Repository layout

See [docs/architecture.md](docs/architecture.md). Android and iOS must stay independently buildable.

## Development setup

1. JDK 17 and Android SDK 35 for Android.
2. Xcode 15+ on macOS for iOS (Linux hosts cannot compile iOS).
3. Copy no secrets into the tree. `android/local.properties` is local-only.

Android:

```bash
echo "sdk.dir=$ANDROID_SDK_ROOT" > android/local.properties
cd android && ./gradlew :app:test :app:assembleSignetDebug
```

## Tests

- Put sanitized fixtures in `test-vectors/`. No real seeds or mainnet descriptors.
- Platform parity tests must consume the same fixtures.
- A payment, backup, restore, or emergency exit is only “working” if a test or documented Signet run actually executed.

## Pull requests

- Small, reviewable diffs.
- Do not include `local.properties`, keystores, or wallet data.
- New user-facing custody claims must match current Second documentation.
