# Terminology

Use these words in UI, docs, and code comments. Do not invent synonyms that change custody meaning.

| Term | Meaning |
| --- | --- |
| Keel | This wallet |
| Bark | Second’s Ark implementation / SDK |
| Ark | The protocol |
| VTXO | Virtual transaction output held in Ark |
| On-chain | Bitcoin base layer |
| Off-chain | Ark protocol balance (not “unconfirmed bitcoin”) |
| Arkoor | Out-of-round Ark payment |
| Board | Move on-chain bitcoin into Ark |
| Offboard | Cooperative move from Ark back on-chain |
| Refresh | Replace VTXOs in a round (new expiry; reduces arkoor trust) |
| Emergency exit | Unilateral on-chain exit without server cooperation |
| Spendable | Available for an immediate Ark payment per Bark `balance().spendableSats` |
| Pending round | Locked in an Ark round |
| Lightning-locked | In an in-flight Lightning send or receive |
| Board-pending | Boarded, waiting for required confirmations |
| Exit-pending | In an emergency exit |
| Seed / recovery phrase | BIP39 mnemonic. Not a complete backup |
| Encrypted backup | Encrypted Bark database snapshot |
| Signet | Test network; coins are worthless |

Never call funds “settled” or “confirmed” without naming the rail and Bark state.
