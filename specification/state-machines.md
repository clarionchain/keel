# State machines (Phase 1)

Illegal transitions throw. Unknown Bark states stay unknown; they are never coerced to `failed`.

## Sync

`idle → syncing → idle | error`

## Ark send

`idle → parsing → invalid | amount_entry → quoting → confirm → authenticating → submitting → reconciling → succeeded | failed_retryable | canceled`

- `submitting` / `reconciling`: persist a client operation id. On process death, reopen and ask Bark before offering Retry.
- UI timeout → stay `reconciling`, not `failed`.

## Ark receive

`idle → address_ready → funds_detected (after sync) → spendable`

Mailbox funds appear through `sync`, not a local “I copied the address” event.

## Backup (Phase 2 implementation; machine defined now)

`idle → snapshotting → encrypting → writing → ok | failed`

Trigger only after a successful mutating Bark call. Generation increment is part of `ok`.
