# QA-43 fixture contract

- `contract.tsv`: six detector rows (`id`, regex, scope, description). The
  scanner compiles the regex from this file; it does not duplicate them in
  code. `CANARY_MARKER` matches the runtime-derived synthetic canary.
- `expectations.tsv`: pinned count contract (minimum source files, pattern
  count, mutant count, report cap, test-only synthetic allowance).
- `clean-sample.kt.txt`: credential-free synthetic Kotlin substrate used for
  the scratch mutation probe.
- `mutants.tsv`: three mutation needles. `{CANARY}` is substituted at run
  time with the derived synthetic canary; each row pins the expected FIRST
  finding code.
- `sha256.txt`: pinned SHA-256 of the four data files above.

Every marker in this directory is invented. No provider address, credential,
PIN, token or recording payload is present.
