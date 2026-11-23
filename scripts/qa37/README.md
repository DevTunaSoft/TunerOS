# QA-37 — FAV-06 bounded series-rule replacement host gate

Deterministic, bounded, host-only **static** gate over pinned git objects. No emulator,
no network, no product edit, no secret. One synthetic sentinel marker only. It audits
the exact C10 commit and reports static evidence only; it does not claim a Kotlin/JUnit
run, an APK build, or any device PASS.

## Owned paths

`scripts/qa37/**` and `qa/fixtures/qa37/**` only. No app source, core source, androidTest,
sibling QA directory, workflow, Gradle file, shared doc or `.hermes/**` was changed.

## Pinned refs (`qa/fixtures/qa37/refs.tsv`)

| Ref | Full SHA | Role |
|---|---|---|
| `BASE` | `6dc10302957925531ee8a0859509d0d5a164743e` | parent of the FAV-06 commit, task-declared base |
| `FIX`  | `f57ab44ce83c9b281e43010c3e5b8dc968a189b6` | C10 `hermes/worker-c10-fav06` HEAD; local == `origin` at audit |

`FIX^ == BASE` is checked directly, as is the exact two-file set changed by the commit.

## Audit boundary — sibling work not duplicated

Read before authoring: QA-29 (b38) DVR terminal-state transitions; QA-30 (b39) bounded
206 partial-content admission; QA-31 (b40) DVR-03 + REL-01 static combination gate;
QA-32 (b41) MAP-04 storage seams and CORE-49 URI redaction; QA-33 (c9) MAP-05 stale-mapping
pruning with one merge hazard marker; QA-34 (b42) combined DVR/MAP dual-hazard gate;
QA-35 (b43) GUIDE-07 recycled-row D-pad window shift. QA-36 (c12) had no committed gate
in any local or remote ref at audit time. A cross-ref path scan over `scripts/qa29..qa36`
and `qa/fixtures/qa29..qa36` returned no `SeriesRuleStore`, `fav06`, `replaceSeriesRules`,
`SeriesReplaceOutcome` or `SeriesRuleTable` marker. QA-37 therefore checks a distinct
invariant: the FAV-06 whole-list replacement policy and its own regression file.

## What is verified (35 fixture rows + 9 structural checks = 44)

Bounded replace and transaction boundary in `SeriesRuleStore.kt` at `FIX`:

- `maxRules <= 0` rejected with a fixed reason; `incoming.size > maxRules` rejected before
  any write; duplicate ids rejected via a `HashSet` built from `incoming` only (no stale
  incoming key is silently collapsed).
- Fresh `all()` snapshot passed into the policy; `clear()` guard; exactly two `table.clear()`
  call sites (guard plus rollback); failed insert clears and restores `current`; success
  returns `Applied`.
- `replaceAll` opens one `beginTransaction`, calls `setTransactionSuccessful` only under
  the `Applied` guard, and ends the transaction in `finally`. The store `clear()` uses
  `db.delete("series_rules", null, null)` and `insert` delegates to `add(rule, now)`.
- The policy table interface and sealed outcome type exist exactly once.

Fav06 regressions at `FIX`, absent at `BASE`:

- The new `fav06/SeriesRuleReplaceTest.kt` is added by `FIX`, not edited from `BASE`.
- Exactly seven `@Test` methods with the seven pinned names, including the over-cap
  no-write/no-clear/no-insert assertions and the failed-insert rollback assertion.
- All rule values in the test are synthetic (`synthetic rule` / `synthetic-channel`);
  no real preference, database, title or channel identifier is used.

No stale-key or credential leak:

- Five `SeriesReplaceOutcome.Rejected("` literals, zero interpolated `Rejected($` reasons —
  rule values are never echoed into a diagnostic.
- Zero sensitive tokens in the added lines of `BASE..FIX` and zero in the report and
  fixtures (URL, e-mail, password/secret/api-key/credential assignment, Bearer token).

Fixture bytes are pinned by `sha256.txt` (3 digests), output is capped at 65,536 bytes /
2,000 lines per git object, and the report carries only check ids, counts, short hashes
and the 55-byte sentinel line.

## Commands and exact observed status

```
bash -n scripts/qa37/run.sh                  PASS
python3 -m py_compile scripts/qa37/mutate.py PASS
bash scripts/qa37/run.sh gate-only           exit 0  checks=44 pass=44 fail=0 first=NONE verdict=PASS
bash scripts/qa37/run.sh verify              exit 0  MUTATION PASS
```

`verify` observations:

```
PREPARE  SeriesRuleStore.kt  sha256=c51a81718802a03e27fa483160c96f07d008f007f8a073659a0b354435e5eab1  bytes=5827
MUTATE   SeriesRuleStore.kt  needle=1  before=c51a8171...  after=ec34f213...
RED      exit 1  checks=44 pass=43 fail=1  first=S_BOUND_GUARD  verdict=FAIL
         CHECK  FAIL  S_BOUND_GUARD  count=0 expected=1
RESTORE  SeriesRuleStore.kt  BYTE_IDENTICAL  sha256=c51a81718802a03e27fa483160c96f07d008f007f8a073659a0b354435e5eab1
RESTORED exit 0  checks=44 pass=44 fail=0  verdict=PASS
GREEN    exit 0  checks=44 pass=44 fail=0  verdict=PASS
```

The single scratch mutation weakens the pinned bound guard from
`incoming.size > maxRules` to `incoming.size > maxRules + 1000`. It is applied only to a
scratch overlay copy of `FIX:SeriesRuleStore.kt`; every other input still comes from the
pinned git object. The overlay is scoped to exactly one `(ref, basename)` pair, and
`restore` proves byte identity against both the saved copy and the git object.

Exit codes: `0` PASS, `1` FAIL (including all mutation RED runs), `2` setup/usage error.

## Reading record

Read for this task: scratch-copy `AGENTS.md`, `CLAUDE.md`, `docs/ROADMAP.md`, the internal
cron doc (`.hermes/tunos/checkpoint.md`), the scratch work-ledger copy
(`docs_HERMES_WORK_LEDGER.md`), the deterministic-qa-gates skill, and the complete read-only
C10 diff (`6dc1030..f57ab44`) plus `SeriesRules.kt` at `FIX`. Sibling QA-31/33/34/35 READMEs
and QA-35 runner/mutator were read for pattern and audit. **Full engineering-book compliance
is not claimed.**
