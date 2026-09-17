# ADR 0007: Repository layout

## Status

Accepted

## Decision

One monorepo: `android/`, `ios/`, `docs/`, `specification/`, `test-vectors/`, `scripts/`, `.github/workflows/`. MIT license at repo root (ClarionChain, 2026).

## Consequences

Platform CI can be independent. A dirty Android build must not block iOS source review and vice versa.
