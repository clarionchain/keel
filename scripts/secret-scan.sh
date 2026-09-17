#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
fail=0

if git grep -I -E -e 'BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY' -- . >/tmp/keel-pem.txt; then
  echo "PEM/private key material found:"
  cat /tmp/keel-pem.txt
  fail=1
fi

# Real BIP39-looking dumps (12+ lowercase words in a quoted string) in non-test files.
if git grep -I -E -e '"[a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+ [a-z]+"' \
  -- ':!android/**/src/test/**' ':!web/**/*.test.ts' ':!test-vectors/**' \
  >/tmp/keel-words.txt; then
  echo "Possible pasted recovery phrase:"
  cat /tmp/keel-words.txt
  fail=1
fi

if git grep -I -E -e 'ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}' -- . >/tmp/keel-tokens.txt; then
  echo "Cloud/API token material found:"
  cat /tmp/keel-tokens.txt
  fail=1
fi

if [[ "$fail" -ne 0 ]]; then
  exit 1
fi
echo "secret-scan: no private keys, tokens, or pasted phrases found"
