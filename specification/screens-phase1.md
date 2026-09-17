# Phase 1 screen and state contract (Android Signet)

Shared with iOS later. Amounts are sats (`Long`). Fiat is optional display.

## App lock / wallet existence

- Cold start: if no wallet → Welcome (Create / Restore). If a wallet exists → Locked screen; biometric/device-credential prompt fires automatically, wallet opens only after authentication (0.5.0). App re-locks after > 60 s in the background; screenshots are blocked app-wide (FLAG_SECURE).
- Create: generate mnemonic → Show seed → Verify two word positions (or explicit skip) → persist mnemonic and create Bark datadir → Home.
- Restore: enter 12 words → validate BIP39 → create/open Bark → Home; or open an encrypted backup file — KEELBK01 (seed only) or KEELDB01 (full state: phrase + datadir snapshot, network/fingerprint/rollback-checked before install).

## Home

Always visible on Signet: **Signet** badge.
Hero: spendable balance in sats (large) + fiat display under it. This is the number the user cares about.
Categories (pending round, Lightning-locked, board-pending, exit-pending, on-chain) appear only when non-zero, each as a plain-language row under "Where the rest of your balance is" — never a jargon summary line.
Empty state (all zeros): numbered next steps (Receive → faucet → Sync now).
Sync state and last successful sync. Sync now action.
Backup health: Phase 1 may show “encrypted backup not configured yet” rather than a fake OK.
Actions: Receive, Send, Sync now.

## Receive

Three modes: Ark address (`newAddress`) with QR + copy; Lightning: enter amount, create BOLT11 invoice (`bolt11Invoice`), QR + copy, poll `isInvoicePaid`, claim via `tryClaimAllLightningReceives` on settle and on every sync; On-chain: board funding address (`boardFundingAddress`) with QR + copy, funds land as on-chain balance and are boarded via "Move to Ark" on Home (`boardAll`).

## Send

Paste or camera-scan (CameraX + ML Kit, on-device decode, CAMERA runtime permission). Parse before confirm. Accepts Ark addresses, BOLT11 invoices (HRP amount prefilled), and Signet Bitcoin addresses (offboard: greedy largest-first VTXO selection, `estimateOffboardFee`, `offboardVtxos`). Amount in sats; fiat preview does not change the sat amount. Confirm screen: type, amount, fee, total debit, Signet, irreversible warning. Spend requires BiometricPrompt or device credential. Lightning pays with `payLightningInvoice(wait = true)` under a 60 s UI timeout; timeout is not failure — reconcile via sync before retrying.

VTXO expiry (0.4.1): after each sync Keel classifies the spendable VTXOs by `expiryHeight` vs chain tip (`VtxoExpiry` in core). Expired VTXOs are excluded from the spendable balance and from offboard coin selection, and are shown on Home with a "Recover on-chain" action (emergency exit). If healthy funds cannot cover a send, quoting fails into `FAILED_RECOVERY` (no retry loop). A definitive expired-VTXO server rejection during submit is recognized by message wording (UniFFI exposes errors as strings only) and also lands in `FAILED_RECOVERY` — never "sync and retry". VTXOs within 144 blocks of expiry show a "Refresh now" action on Home (`getVtxosToRefresh` + `refreshVtxos`).

## Emergency exit

Settings → Emergency exit. Explains unilateral exit in plain language, lists `getExitVtxos()` with per-VTXO state (started/processing/waiting on timelock/claimable/claiming/claimed), "Start exit for entire wallet" behind a warning dialog + biometric (`startExitForEntireWallet`). Sync calls `syncPendingBoards` + `syncExits`; Home shows an "Emergency exit in progress" banner while `hasPendingExits()`.

## History

Home shows an Activity list from Bark `history()`: direction, amount, kind (Ark/Lightning/board/offboard/exit/refresh), non-final status, timestamp. Latest 20.

## Settings (minimal)

Network (Signet, read-only), app/Bark versions, reveal seed (authenticated), delete wallet (double confirm). Backups (0.5.0): export encrypted seed backup, export full wallet backup (KEELDB01), auto-backup status + restore-from-local-auto-backup (two-tap confirm). Emergency exit entry point.
