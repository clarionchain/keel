# Release process

Versioning is deterministic (calendar or semver decided at first tagged RC). Do not publish to Google Play, App Store, GitHub Releases, or TestFlight without an explicit ClarionChain authorization for that action.

## Gates

- Clean git worktree
- Unit / parity tests
- Signet manual report
- SBOM for the release artifacts
- Secret scan of history and worktree
- Mainnet still disabled unless a written enablement exists

## Signing

Production Android/iOS signing keys stay off this repository and off CI secrets until authorized. Debug keys are local-only.

## Reproducibility

Work toward reproducible Android builds. Apple’s iOS reproducibility limits must be documented honestly, not claimed away.
