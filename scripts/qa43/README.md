# QA-43 source hygiene gate

Deterministic, offline, zero-dependency gate over **committed** `app/src/**` and
`core/src/**` Kotlin/Java sources and tests. It detects regressions of the kind
fixed by SEC-01 (bare credential assignment reaching a shareable summary) and
CORE-49 / SEC-04 (hostile URI token or private path reaching diagnostics). It
only reads the scanned files.

```
bash scripts/qa43/run.sh
python3 scripts/qa43/source_hygiene_gate.py scan
python3 scripts/qa43/source_hygiene_gate.py probe --scratch <native-dir>
```

Exit codes: `0` PASS, `1` FAIL, `2` BLOCKED. Counts are printed as
tab-delimited `CHECK` / `SCAN` / `PROBE` lines. Findings are reported as
`path:line:code` only; matched text is never printed.

The pattern contract lives in `qa/fixtures/source-hygiene43/contract.tsv`, not
in the scanner, so the detector is data-driven and auditable. The canary used
by the mutation probe is derived at run time from a fixed seed and never
appears as a literal in source or fixtures. All fixture values are synthetic;
no real URL, credential, PIN, token or recording payload is used.

`probe` copies `clean-sample.kt.txt` into a scratch directory, applies each
row of `mutants.tsv`, and requires a nonzero finding with the pinned FIRST
code. It then restores the scratch copy and re-hashes every owned committed
file to prove byte identity.
