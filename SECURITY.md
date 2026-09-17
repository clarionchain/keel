# Security policy

Keel is experimental financial software. Treat reports that can steal funds, extract keys, or corrupt backups as urgent.

## How to report

**Do not open a public GitHub issue** for a vulnerability that could lose bitcoin or expose a seed, backup, or private key.

1. Prefer [GitHub private vulnerability reporting](https://github.com/clarionchain/keel/security/advisories/new) on this repository.
2. If that is unavailable, email **dev@clarionchain.io** with a description, affected version/commit, and steps that do **not** include real seeds or mainnet keys.

We will acknowledge reports as soon as practical and keep sensitive details private until a fix is available or the issue is disclosed by agreement.

## Please include

- Keel version / git commit
- Platform (Android/iOS) and OS version
- Whether the report is Signet or (if ever enabled) mainnet
- Impact (theft, lockout, backup rollback, secret leakage, and so on)

Never send a real mnemonic, decrypted backup, or production signing key.

## Scope

In scope: the Keel applications, backup format, build scripts, and how we integrate Bark.

Out of scope unless Keel mishandles them: bugs solely in upstream Bark, the Ark server, or Esplora. Please also report those to Second when they belong upstream.
