# QA-38 — RESTORE-02 bounded backup-restore integration host gate

Deterministic, bounded, host-only **static** gate over pinned git objects. No emulator,
no network, no product edit, no Gradle, no PR and no merge. One synthetic marker only.
At the audited pin the gate reports a truthful **FAIL**: C13 has not yet pushed the
RESTORE-02 production change or its regressions.

## Owned paths

`scripts/qa38/**` and `qa/fixtures/qa38/**` only. No app source, core source,
androidTest, sibling QA directory, workflow, Gradle file, shared document or
`.hermes/**` was changed. Base worktree SHA:
`a523c2e43d198ee752f7c4f03f9f7853356ffb30`.

## Pinned inputs (full SHA; a moving branch name is never trusted)

| Ref | Full SHA | Role |
|---|---|---|
| `BASE` | `a523c2e43d198ee752f7c4f03f9f7853356ffb30` | reconciled main base |
| `C10` | `f57ab44ce83c9b281e43010c3e5b8dc968a189b6` | FAV-06 bounded atomic series-rule replacement |
| `C13` | `c1d6f62bdba4f47edc069c437022131dc45d1da6` | **observed** `hermes/worker-c13-restore02` HEAD at audit; local == origin |
| `QA37` | `9df525ad5c1b11f8625949888474b24f86967b81` | B44 QA-37 FAV-06 host gate |

`C13` is pinned to the branch head actually observed at audit time. Its only delta
from `BASE` is `app/build.gradle.kts` (a JUnit 4 test dependency). It is **not** a
RESTORE-02 final commit. `git ls-remote origin refs/heads/hermes/worker-c13-restore02`
returned the same SHA. No ref in the repository contains a `BackupRestore.kt` with
`replaceAll`, and no ref contains a `restore02` test path.

## Audit boundary — QA-29..37 read first, nothing duplicated

- **QA-29 (b38):** bounded DVR final-state transitions.
- **QA-30 (b39):** bounded 206 partial-content admission.
- **QA-31 (b40):** static-needle DVR-03 + REL-01 combination gate.
- **QA-32 (b41):** MAP-04 storage seams and CORE-49 URI redaction.
- **QA-33 (c9):** MAP-05 stale-mapping pruning with one merge-hazard marker.
- **QA-34 (b42):** combined DVR/MAP dual-hazard gate; cross-pins QA-31 and QA-33.
- **QA-35 (b43):** GUIDE-07 recycled-row D-pad window shift.
- **QA-36 (c12):** HTTP-07 malformed-URL redaction and CORE-49 structural check.
- **QA-37 (b44):** detailed FAV-06 policy proof at the same `C10` SHA — over-cap and
  duplicate refusal before any write, failed-insert rollback, transaction boundary,
  `replaceAll` signature and the FAV-06 regression file.

QA-37 already owns the FAV-06 policy assertions. QA-38 therefore does **not**
re-check those policy needles against `C10` source. Following QA-34's precedent, it
cross-references QA-37: it pins QA-37 by full SHA, confirms QA-37's own fixture pins
the identical `C10` object, confirms QA-37's runner exists, and confirms QA-37's
fixture still names the three required properties (over-cap, duplicate, rollback).
QA-38's distinct subject is the **integration seam**: whether `BackupRestore.apply`
at the pinned C13 commit actually routes through that policy and drops the unchecked
remove-all/add-each loop, and whether RESTORE-02 regressions exist.

## What is verified (11 fixture rows + 11 structural checks = 22)

- QA-37 pin integrity: identical `C10` object, runner present, three coverage names
  present; four pinned refs distinct, resolvable and sharing the common base.
- Pin freshness: the observed `origin/hermes/worker-c13-restore02` head equals the
  pinned C13 SHA. When C13 pushes a final commit this check FAILs until `refs.tsv`
  is re-pinned, so no stale pin can be silently reused.
- Baseline control at `BASE`: the unchecked
  `for (rule in series.all()) series.remove(rule)` /
  `for (rule in contents.seriesRules) series.add(rule, now)` loop exists and no
  `series.replaceAll(` call exists.
- RESTORE-02 integration at pinned `C13` (the four task questions):
  1. `BackupRestore.apply` no longer uses the unchecked remove-all/add loop —
     `C13_NO_LOOP_REMOVE`, `C13_NO_LOOP_ADD`, `C13_NO_UNGUARDED_ADD`.
  2. Over-cap and duplicate refusal before any write — delegated to the pinned
     QA-37 gate via `X_QA37_PINS_C10`, `X_QA37_COVERS_OVERCAP`,
     `X_QA37_COVERS_DUP`, `X_QA37_COVERS_ROLLBACK`, `QA37_GATE_PRESENT`; the
     integration path must route through it via `C13_USES_REPLACEALL`.
  3. Failed insert leaves previous rules intact — same QA-37 delegation plus
     `X_QA37_COVERS_ROLLBACK`; no partial restore is accepted by QA-38 itself.
  4. RESTORE-02 regressions exist — `C13_RESTORE02_TEST_PRESENT` (tree scan).
- `C13_BACKUP_CHANGED` proves the restore blob differs from the common base.
- Fixture SHA-256, no production drift, no sensitive patterns in report or fixtures.
- One bounded synthetic marker (`RESTORE-02-INTEGRATED`, 52 bytes) is emitted only
  when every RESTORE-02 integration condition holds. At the audit pin the marker is
  absent and `RESTORE02_MARKER_EMITTED` FAILs.

## Verdict and exit codes

Exit 0 PASS (all 22 checks green and RESTORE-02 integrated), 1 FAIL, 2 SETUP error.
At the pinned SHAs the truthful exit is **1 / FAIL**, with 15 PASS and 7 FAIL;
`first=C13_NO_LOOP_REMOVE`, `marker=0`. A false PASS is not emitted.

## Commands and exact observed status

```
bash -n scripts/qa38/run.sh                                  PASS
python AST parse + compile scripts/qa38/mutate.py            PASS
sha256sum -c qa/fixtures/qa38/sha256.txt                     3/3 OK
bash scripts/qa38/run.sh gate-only                           EXIT=1
    SUMMARY checks=22 pass=15 fail=7 first=C13_NO_LOOP_REMOVE
            verdict=FAIL expected-total=22 pinned-c13=c1d6f62b marker=0
    FAIL ids: C13_NO_LOOP_REMOVE, C13_NO_LOOP_ADD, C13_USES_REPLACEALL,
             C13_NO_UNGUARDED_ADD, C13_RESTORE02_TEST_PRESENT,
             C13_BACKUP_CHANGED, RESTORE02_MARKER_EMITTED
bash scripts/qa38/run.sh verify                              EXIT=0
    RED:      rc=1 fail=8 first=X_QA37_COVERS_DUP marker=0
    RESTORE:  4/4 scratch files BYTE_IDENTICAL (vs pinned git blobs)
    GREEN:    rc=1 fail=7 first=C13_NO_LOOP_REMOVE verdict=FAIL marker=0
    MUTATION PASS: single-scratch-mutation-detected-and-restored-byte-identically
```

The single mutation removes one byte-needle from a scratch overlay of
`QA37:qa/fixtures/qa37/expectations.tsv`
(`!ids.add(rule.id)` -> `!ids.add(rule.unusedId)`). It is scoped to exactly one
declared `(ref, path)` pair; a missing declared substitution fails closed. No
repository or production file is written by the mutator. Because the baseline is
already FAIL for absent C13 work, sensitivity is demonstrated by a named positive
check flipping PASS -> RED at a pinned FIRST code, then restoring byte-identically
and returning to the identical baseline verdict.

## Bounds and privacy

Only four pinned commits and their fixed paths are read, capped at 65,536 bytes /
2,000 lines each. No network, emulator, device, unbounded walk or fixture-content
echo. The report carries check ids, counts, short SHAs and hashes only. Report and
fixtures are scanned for URL, credential and secret patterns. All markers and names
are synthetic; no provider data, real media or credentials are used. LF is pinned
with `.gitattributes` in both owned directories; a native-prefix checkout simulation
produced 8 files with zero CR bytes. Device execution is **NOT RUN** and no device
PASS is claimed. JDK 17 is installed at the assigned path but this static gate uses
only bash, python and git.

## Reading record and blockers

Read for this task: `AGENTS.md`, `CLAUDE.md`, `docs/ROADMAP.md`, the scratch copies
`docs_HERMES_EXECUTION_ROADMAP.md`, `docs_HERMES_INTERNAL_CRON.md` and
`docs_HERMES_WORK_LEDGER.md`, the `deterministic-qa-gates` skill with its
`byte-exact-fixtures` reference, and the read-only diffs of C10 FAV-06 and C13
RESTORE-02. Full engineering-book compliance is **NOT** claimed.

Blocker: C13 RESTORE-02 is incomplete at the observed pin — `BackupRestore.apply`
still uses the unchecked loop and no restore02 regression file exists. This gate
reports that honestly as FAIL. Next task: when C13 pushes its final RESTORE-02
commit, re-pin `C13` in `qa/fixtures/qa38/refs.tsv`, refresh `sha256.txt`, and re-run
`gate-only` and `verify`; the expected result is EXIT=0 / verdict=PASS with one
bounded marker. No PR or merge is opened by this worker.
