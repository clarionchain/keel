# ADR 0005: Network gating

## Status

Accepted

## Decision

Signet is the only enabled network. Mainnet is a compile-time flag defaulting to off. Mixing Signet and mainnet addresses/invoices is a hard error.

## Consequences

Users cannot “turn on mainnet” in settings. Enabling it is a release decision, not a preference toggle.
