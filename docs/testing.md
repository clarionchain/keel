# Testing

## PWA

```bash
cd web
npm ci
npm test
```

Manual Signet: open https://clarionlab.dev/keel (or `npm run dev` in `web/`), create a wallet, receive from https://signet.2nd.dev/, send a small Ark payment. Confirm the browser console never prints the seed.

## Android

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
echo "sdk.dir=$ANDROID_SDK_ROOT" > android/local.properties
cd android
./gradlew :core:test :app:test :app:assembleSignetDebug
```

Manual Signet:

1. Install the Signet debug APK.
2. Create a wallet. Write the seed on paper, then confirm words in-app.
3. Receive from https://signet.2nd.dev/ to the shown Ark address.
4. Sync; spendable balance must increase.
5. Send a small Ark payment to an address you control.
6. Confirm the UI did not log the mnemonic (`adb logcat` should contain no seed).

A step is only “verified” if it actually ran.

## iOS

Compile on macOS/Xcode or GitHub `macos-latest`. Linux hosts cannot verify an iOS compile.

## Local regtest

Keel has a `regtest` product flavor (`io.clarionchain.keel.regtest`) for a local [cashu-regtest](https://github.com/callebtc/cashu-regtest) `--bark` stack. Point `BARK_SERVER` / `BARK_BITCOIND` in `android/app/build.gradle.kts` at **your** Ark and bitcoind RPC (emulator host is usually `10.0.2.2`; a phone on LAN uses that machine’s private IP). RPC user/pass must match your local bitcoind. Do not commit lab hostnames, Tailscale addresses, or non-default credentials.

```bash
cd android && ./gradlew :app:assembleRegtestDebug
```

Useful matrix on regtest (slow or awkward on public Signet): board, Ark send both directions, Lightning in/out, offboard, and emergency exit with the Ark server container stopped.
