# Keel

Self-custodial Bitcoin wallet for [Ark](https://second.tech), Lightning, and on-chain payments. It is a thin wrapper around Second’s [Bark](https://second.tech/docs/bark-sdk) SDK: Bark owns protocol and keys, Keel owns the UI, at-rest seed encryption, backups, and when to sync.

Keys stay on the device. There is no Keel account, cloud login, or required backend.

**Experimental. Signet only — test coins, not real bitcoin.** Mainnet is compile-time gated off.

- Try it: [clarionlab.dev/keel](https://clarionlab.dev/keel) (browser PWA; Android APK from the same page)
- Source: [github.com/clarionchain/keel](https://github.com/clarionchain/keel)
- App ID: `io.clarionchain.keel`
- License: [MIT](LICENSE)

## What works

- **PWA** (`web/`): create, restore, receive, send (Ark / Lightning / on-chain), emergency exit, seed backup
- **Android** (`android/`): same flows, plus biometric/PIN lock and background VTXO renewal
- **iOS**: not shipped yet

See [docs/known-limitations.md](docs/known-limitations.md) and [docs/recovery.md](docs/recovery.md). The 12-word seed is **not** a complete backup by itself.

## Default Signet endpoints

- Ark: `https://ark.signet.2nd.dev`
- Esplora: `https://mempool.space/signet/api`
- Faucet: `https://signet.2nd.dev/`

## Build

### PWA

```bash
cd web
npm ci
npm test
npm run build
```

Serves from `web/dist/` (this repo copies that into `dist/` for https://clarionlab.dev/keel).

### Android

JDK 17 and Android SDK API 35. `android/local.properties` is gitignored — do not commit SDK paths or signing keys.

```bash
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
echo "sdk.dir=$ANDROID_SDK_ROOT" > android/local.properties
cd android
./gradlew :core:test :app:test :app:assembleSignetDebug
```

## Security

Report vulnerabilities privately — see [SECURITY.md](SECURITY.md). Never commit seeds, keys, wallet databases, or decrypted backups.

## Trademark

Keel is the working name of this open-source wallet. An unrelated regulated fintech named Keel Money exists. Do not assume app-store or trademark clearance.
