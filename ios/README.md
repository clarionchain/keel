# iOS (Phase 3)

Keel’s iOS app is an independent SwiftUI project. It is **not** compiled on Linux (no Xcode).

Until Phase 3:

- Behavioral source of truth: `../specification/`
- Fixtures: `../test-vectors/`
- Bark Swift pin: `0.22.0+bark-0.6.2` from `https://gitlab.com/ark-bitcoin/bark-ffi-bindings.git`
- Bundle ID: `io.clarionchain.keel`

A Linux CI job must not claim an iOS build passed.
