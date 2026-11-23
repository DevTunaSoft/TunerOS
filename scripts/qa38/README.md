# QA-38 — RESTORE-02 bounded backup-restore integration host gate

Deterministic, bounded, host-only **static** gate over pinned git objects. No emulator,
no network, no product edit, no Gradle, no PR and no merge. One synthetic marker only.

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
| `C13` | `fc8986725c8fa3cda4acc429266526b9a237d90d` | RESTORE-02 final commit; local == origin |
| `QA37` | `9df525ad5c1b11f8625949888474b24f86967b81` | B44 QA-37 FAV-06 host gate |

Pin history, stated exactly: at the first audit the observed C13 branch head was
`c1d6f62bdba4f47edc069c437022131dc45d1da6`, whose only delta from `BASE` was
`app/build.gradle.kts` (JUnit 4 dependency); the RESTORE-02 change was absent and the
gate truthfully reported FAIL at that SHA. C13 subsequently pushed
`fc8986725c8fa3cda4acc429266526b9a237d90d` (`RESTORE-02: bound series-rule restore
and fail closed on refusal`). The pin was updated to that final SHA and the gate now
reports PASS. Both SHAs are recorded here; nothing was fabricated. The pin-freshness
check compares the pin to `origin/hermes/worker-c13-restore02` on every run.

## Audit boundary — QA-29..37 read first, nothing duplicated

- **QA-29 (b38):** bounded DVR final-state transitions.
- **QA-30 (b39):** bounded 206 partial-content admission.
- **QA-31 (b40):** static-needle DVR-03 + REL-01 combination gate.
- **QA-32 (b41):** MAP-04 storage seams and CORE-49 URI redaction.
- **QA-33 (c9):** MAP-05 stale-mapping pruning with one merge-hazard marker.
- **QA-34 (b42):** combined DVR/MAP dual-hazard gate; cross-pins QA-31 and QA-33.
- **QA-35 (b43):** GUIDE-07 recycled-row D-pad window shift.
- **QA-36 (c12):** HTTP-07 malformed-URL redaction and CORE-49 structural check.
- **QA-37 (b44):** detailed FAV-06 policy proof at the same `C10` SHA.

QA-37 already owns the FAV-06 policy assertions. QA-38 does **not** re-check them
against `C10` source. Following QA-34's precedent it cross-pins QA-37 by full SHA,
confirms QA-37's fixture pins the identical `C10` object, and confirms QA-37's runner
exists. QA-38's distinct subject is C13's `BackupRestore.apply`: whether the unchecked
remove-all/add-each loop is gone, whether the whole series-rule list is refused before
any write, whether a failed insert restores the previous list, and whether RESTORE-02
regressions exist. C13 implements its own caller-side policy rather than calling
C10's `replaceAll`, so the gate checks the code that actually shipped.

## What is verified (16 fixture rows + 14 structural checks = 30)

- Pin integrity: four distinct resolvable refs, common base, observed C13 head equals
  the pin, QA-37 cross-pin and runner present, fixture SHA-256.
- Baseline control at `BASE`: the unchecked
  `for (rule in series.all()) series.remove(rule)` /
  `for (rule in contents.seriesRules) series.add(rule, now)` loop exists and
  `ruleRefusal(` does not.
- **No unchecked loop in `apply`:** `C13_APPLY_BODY_NO_UNCHECKED_LOOP` scopes the
  scan to the `fun apply` body (lines 37-55) and finds zero `series.remove(rule)` /
  `series.add(rule, now)` calls; `apply` calls `ruleRefusal(...)` then
  `replaceSeriesRules(...)`.
- **Over-cap and duplicate refusal before any write:** `ruleRefusal` checks
  `rules.size > SeriesRules.MAX_RULES` and duplicate ids via a `HashSet`;
  `C13_REFUSAL_BEFORE_WRITE` proves the refusal (line 40) precedes the first store
  write (line 45) and the replacement call (line 47). Fixed refusal reasons are
  present; no rule value is interpolated into a diagnostic.
- **Failed insert leaves previous rules intact:** `replaceSeriesRules` snapshots
  `series.all()`, restores via `restoreSeriesRules(previous, now)` on a failed add
  and on the final set mismatch, and throws a fixed reason.
  `C13_RESTORE_AFTER_FAILED_INSERT` proves the failed-add guard (line 68) precedes
  the restore (line 69).
- **RESTORE-02 regressions exist:** six paths under
  `app/src/test/java/com/devtunasoft/tuneros/restore02/`, including
  `BackupRestoreSeriesTest.kt`.
- No production drift, no sensitive patterns in report or fixtures. One bounded
  synthetic marker (`RESTORE-02-INTEGRATED`, 52 bytes) is emitted only when all
  RESTORE-02 conditions hold.

## Verdict and exit codes

Exit 0 PASS, 1 FAIL, 2 SETUP error. At the pinned SHAs the observed result is
**0 / PASS**: 30 checks, 30 PASS, 0 FAIL, `first=NONE`, `marker=1`.

## Commands and exact observed status

```
bash -n scripts/qa38/run.sh                                  PASS
python AST parse + compile scripts/qa38/mutate.py            PASS
sha256sum -c qa/fixtures/qa38/sha256.txt                     3/3 OK
bash scripts/qa38/run.sh gate-only                           EXIT=0
    SUMMARY checks=30 pass=30 fail=0 first=NONE verdict=PASS
            expected-total=30 pinned-c13=fc898672 marker=1
    RESTORE02 RESTORE-02-INTEGRATED sha=fc898672 tests=6
bash scripts/qa38/run.sh verify                              EXIT=0
    RED:      rc=1 fail=2 first=C13_REFUSAL_THROW marker=0
    RESTORE:  4/4 scratch files BYTE_IDENTICAL (vs pinned git blobs)
    GREEN:    rc=0 fail=0 first=NONE verdict=PASS marker=1
    MUTATION PASS: single-scratch-mutation-detected-and-restored-byte-identically
```

The single mutation removes one byte-needle from a scratch overlay of
`C13:app/src/main/java/com/devtunasoft/tuneros/BackupRestore.kt`
(`if (refusal != null) throw IllegalStateException(refusal)` ->
`if (1 == 0) throw IllegalStateException(refusal)`). It is scoped to exactly one
declared `(ref, path)` pair; a missing declared substitution fails closed. No
repository or production file is written by the mutator. RED fires the named refusal
check at a pinned FIRST code, then restoration returns all four scratch files to
byte-identical copies of the pinned git blobs and the gate returns to its PASS verdict.

## Bounds and privacy

Only four pinned commits and their fixed paths are read, capped at 65,536 bytes /
2,000 lines each. No network, emulator, device, unbounded walk or fixture-content
echo. The report carries check ids, counts, line numbers, short SHAs and hashes only.
Report and fixtures are scanned for URL, credential and secret patterns. All markers
and names are synthetic; no provider data, real media or credentials are used. LF is
pinned with `.gitattributes` in both owned directories. Device execution is **NOT RUN**
and no device PASS is claimed. JDK 17 is installed at the assigned path but this
static gate uses only bash, python and git. This is a static evidence gate, not a
Kotlin/JUnit run, an APK build or an on-device acceptance result.

## Reading record and next task

Read for this task: `AGENTS.md`, `CLAUDE.md`, `docs/ROADMAP.md`, the scratch copies
`docs_HERMES_EXECUTION_ROADMAP.md`, `docs_HERMES_INTERNAL_CRON.md` and
`docs_HERMES_WORK_LEDGER.md`, the `deterministic-qa-gates` skill with its
`byte-exact-fixtures` reference, and the read-only diffs of C10 FAV-06 and C13
RESTORE-02. Full engineering-book compliance is **NOT** claimed.

No blocker for the host gate. Next task: integrate the verified branch, then run the
real JVM unit tests and exact-commit CI/emulator acceptance for C13's six RESTORE-02
test files; this host gate does not substitute for those runs. No PR or merge is
opened by this worker.
