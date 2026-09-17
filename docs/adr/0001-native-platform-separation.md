# ADR 0001: Native platform separation

## Status

Accepted

## Decision

Ship independent Kotlin/Compose Android and Swift/SwiftUI iOS apps. No shared UI framework.

## Context

Calle / Second’s SDK model is native bindings over Rust Bark. Cross-platform UI would fight UniFFI, store review, and background-execution rules.

## Consequences

Duplicate UI work. Shared `specification/` and `test-vectors/` are mandatory for parity.
