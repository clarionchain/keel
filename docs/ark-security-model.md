# Ark security model (Keel)

Based on current Second documentation, not marketing copy.

## Custody

Keel is self-custodial. The seed and Bark database on the device authorize spends. The Ark server is a protocol counterparty, not a Keel account. Basic wallet use must not require email, phone, or a proprietary cloud.

## What the Ark server can and cannot do

- It coordinates rounds, Lightning bridging, and mailboxes.
- **Board and round VTXOs** can be unilaterally emergency-exited on-chain.
- **Out-of-round (arkoor) received VTXOs** can be double-spent if the *sender and the server both* collude. Refreshing those VTXOs in a round removes that extra trust. Keel must refresh received out-of-round VTXOs and explain this in human language, not protocol jargon.
- If the server is down, cooperative offboard and Lightning fail. Unilateral exit remains the self-custody path, and it needs **confirmed on-chain funds** for CPFP plus a preserved database.

## VTXO lifetime

VTXOs expire. They must be refreshed in a round before expiry or the user must exit. Keel must show health in plain categories (healthy / refresh soon / urgent) and must not claim funds are maintenance-free. Mobile OS background limits mean we cannot promise exact-time refresh; we warn early and offer a one-tap foreground action.

## Emergency exit

Broadcasting the exit tree is multi-step and fee-heavy. Cancellation is only safe before the final exit transaction is broadcast (Bark `cancelExit`). Funds on an exit output are not seed-only spendable until claimed. Keel must show every lifecycle state and required user action.

## Payment confirmation

Before signing/sending, show: destination type (Ark / Lightning / on-chain), amount in sats, fee or estimate, total debit, network (Signet is always visible), and that the action can be irreversible. Parse and validate network before the confirm screen. Reject mainnet invoices/addresses on a Signet build.
