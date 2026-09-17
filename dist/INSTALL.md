# Keel — Signet test build

**Experimental. Test coins only. Never send real bitcoin.**

## Install on Android

1. Download `keel-0.5.5-signet.apk` to your Android phone.
2. When Android asks, allow "install unknown apps" for your browser/Files app.
3. Open the APK to install. It is a debug build, signed with a local debug key.

## Verify the download (optional)

```
sha256sum keel-0.5.5-signet.apk
# must match keel-0.5.5-signet.apk.sha256
```

## Use it

- Create a wallet, write the 12 words on paper, confirm the words in-app or skip the check.
- The app locks at launch and after 1 minute in the background (fingerprint/face/PIN). Screenshots are blocked app-wide.
- Get free Signet coins from https://signet.2nd.dev/ to your Ark address.
- Receive via Ark address, Lightning invoice, or on-chain Bitcoin (then "Move to Ark" on Home).
- Send to an Ark address, Lightning invoice, or Bitcoin address; paste it or scan the QR code.
- Emergency exit (Settings) pulls all funds on-chain without the Ark server.
- Expired Ark funds are flagged on Home with a "Recover on-chain" action. Expiry protection is automatic (0.5.4): every sync signs delegated "renewal appointments" with the Ark server for all funds, so they are refreshed even while the app is closed. On Android (0.5.5) a background task wakes the wallet every ~12 hours to renew by itself — you don't need to remember to open the app.
- Backups: the app auto-saves an encrypted snapshot after every payment/board/exit (Settings shows the last one). For off-device backup use Settings → Export full wallet backup (seed + history + in-progress exits, your passphrase, restores without the Ark server). The seed-only export is still there too.
- Send/receive uses the Ark test network only. Mainnet is disabled in this build.

## Report issues

Tell the Keel dev chat what happened, including whether the error appeared on the screen or in `adb logcat`.
