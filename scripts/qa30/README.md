# QA-30 bounded 206 partial-content admission gate

One deterministic, zero-new-dependency host gate over the pure-Kotlin production
unit `HlsContentRange.kt` (with its `HlsByteRange` and bounded-line dependencies).
No production file is edited by this task; the gate compiles the real sources.

## Contract

A reply field of the form `bytes <from>-<to>/<total>` is admitted only when
`from` and `to` match the requested offset and `offset + length - 1` exactly.
A wildcard total is allowed; a numeric total must exceed `to`. Missing,
malformed, padded, oversized or overflowing inputs are refused before any media
is appended. All scratch identifiers and counts are invented.

## Layout

- `qa/fixtures/qa30/` pinned scratch fixtures and `sha256.txt` manifest.
- `scripts/qa30/Qa30Gate.kt` host runner (`generate`, `verify`, `mutant`).
- `scripts/qa30/mutate.py` fixture mutants; `mutate_source.py` production-copy mutants.
- `scripts/qa30/privacy_scan.py` independent diagnostics/fixture privacy scan.
- `scripts/qa30/run.sh` bounded RED-then-GREEN driver with distinct pinned exit codes.
- `scripts/qa30/agp_compile.sh` offline androidTest compile/package attempt.
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa30/` CI mirror.

## Checks

`verify` runs 30 named checks under `-Xmx96m`: fixture privacy, strict headers,
field bounds, pinned case cap, dimensions, manifest, byte identity, nine direct
production guard probes, eight per-family admission checks, corrupt-input
fail-closed, a permissive-reader control, a synthetic leak detector, a golden
digest, and heap/thread bounds. `mutant` mode skips byte identity, manifest and
golden-file comparisons so a semantic mutant reaches its named guard.

## Use

    scripts/qa30/run.sh generate     # rewrite pinned fixtures deterministically
    scripts/qa30/run.sh verify       # full RED (15) and GREEN (30) run
    scripts/qa30/run.sh gate-only    # GREEN and privacy only

Each RED mutant must exit with its own pinned code and print the matching
`first=<CHECK>` line. The driver restores every scratch copy and re-hashes every
production and fixture source before reporting RESTORE PASS.
