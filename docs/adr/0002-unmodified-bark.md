# ADR 0002: Unmodified Bark

## Status

Accepted

## Decision

Use official Bark bindings only. Do not reimplement protocol or crypto. Do not fork Bark to ship Keel.

## Consequences

Keel’s release cadence follows pinned Bark tags. SDK gaps (for example no snapshot API) are documented and, if blocking, raised upstream rather than papered over.
