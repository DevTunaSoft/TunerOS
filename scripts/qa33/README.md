QA-33 - host-only deterministic gate for MAP-05 stale-mapping pruning
=====================================================================

Owned paths only: scripts/qa33/**, qa/fixtures/qa33/**. No product source,
Gradle, manifest, workflow, shared document or sibling QA path is changed.
No PR is opened and no merge is performed by this worker.

Pinned inputs (full SHA, never a moving branch name)
----------------------------------------------------
BASE a523c2e43d198ee752f7c4f03f9f7853356ffb30  (merge base of C5 and C8)
C5   9d27d9e25ec3a72a5d7bade03d513546d95a9b3a  MAP-04, PR 210
C8   fd4db647db31ce6b5bd9b1d193c356857e9c7122  MAP-05, PR 213

Audit boundary (read before building this gate)
-----------------------------------------------
QA-29 (worker-b38) gates DVR terminal-state transitions by compiling pure
production Kotlin; QA-30 (worker-b39) gates bounded 206 partial-content
admission; QA-31 (worker-b40) is the host-only pinned-SHA static combination
gate for DVR-03 + REL-01 and is the pattern reused here; QA-32 (worker-b41)
had no committed gate under scripts/qa32 in either the local or remote ref at
audit time (only the JUnit-4 dependency commit). None of those gates reads
GuideMappingStore.retain, map05 fixtures, or the C5/C8 shared-file hazard.
QA-33 therefore adds a distinct static check set and does not duplicate them.

What is verified
----------------
- GuideMappingStore at C8 exposes exactly one companion `retain(mappings,
  validKeys)` helper. Empty validKeys (or empty mappings) returns the input
  unchanged - fail closed. Membership is checked, an unchanged map is returned
  by identity, allocation is `LinkedHashMap(mappings.size)` and never sized by
  the playlist, values and iteration order are preserved, and the caller map is
  never mutated.
- MainActivity at C8 calls retain exactly once, inside a bounded onSuccess
  window anchored on the unique `channels = it.channels` assignment. The window
  contains the non-empty saved-mapping guard, live-key construction from the
  playlist, the retain call, the `pruned !== guideMappings && save(pruned)`
  change-only guard, and the guarded reassignment in strictly increasing line
  order. No bare `save(guideMappings)` and no retain call exist in that window
  or elsewhere in the file.
- The ten named map05 regressions exist at C8: `@Test` count is exactly 10 and
  each pinned test method name occurs once.
- BASE has no retain or MAP-05 marker. C5 has the MAP-04 storage/codec seams
  and no retain. C8 has retain and no C5 storage/codec seams. C5 and C8 share
  BASE as their merge base and both change GuideMappingStore.kt relative to
  BASE.

Merge-review hazard and verdict
-------------------------------
Because C5 (MAP-04) and C8 (MAP-05) both rewrite GuideMappingStore.kt from the
same base, neither branch contains the other fix. The gate does not report a
false PASS for that state: it emits exactly one bounded, synthetic marker line

  HAZARD  MERGE-REVIEW-NEEDED  shared=<store path>  base=<8>  c5=<8>  c8=<8>

and sets `verdict=MERGE-REVIEW-NEEDED`. Marker text comes from a pinned fixture,
the line is capped at 200 bytes, and exactly one such line is required. Exit
codes are 0 PASS (hazard absent), 1 FAIL (any check fails), 3
MERGE-REVIEW-NEEDED (all checks pass, hazard present). At the pinned SHAs the
observed exit is 3, which is the truthful result for this integration state.

Commands and exact observed status
----------------------------------
  bash -n scripts/qa33/run.sh                       PASS
  python3 -m py_compile scripts/qa33/mutate.py      PASS
  bash scripts/qa33/run.sh gate-only                EXIT=3
      SUMMARY checks=48 pass=48 fail=0 first=NONE
              verdict=MERGE-REVIEW-NEEDED expected-total=48
              base=a523c2e4 c5=9d27d9e2 c8=fd4db647
  bash scripts/qa33/run.sh verify                   EXIT=0
      RED:   rc=1, G_FAIL_CLOSED_EMPTY hits=1, first=G_FAIL_CLOSED_EMPTY
      RESTORED: rc=3, checks=48 pass=48 fail=0, marker=1
      GREEN: rc=3, checks=48 pass=48 fail=0, marker=1
      RESTORE: 5/5 scratch copies BYTE_IDENTICAL, digests match pinned git objects
      MUTATION PASS: single-scratch-mutation-detected-and-restored

The one RED mutation replaces the fail-closed empty-validKeys line in a scratch
overlay of the C8 store with a permissive mappings-only guard. No repository or
production file is written. The overlay is scoped to exactly the declared
(C8, GuideMappingStore.kt) pair; a missing declared substitution fails closed.

Bounds and privacy
------------------
Only three pinned commits and five fixed paths are read (65,536 bytes /
2,000 lines each, except MainActivity at 262,144 bytes / 4,096 lines). The
onSuccess window is capped at 40 lines; the report is a fixed list of check
ids, counts, line numbers and short digests. No network, emulator, device,
unbounded walk or fixture-content echo. Report and fixtures are scanned for
URL, credential and secret patterns. All names and tokens are synthetic; no
provider data, real media or credentials are used. Device execution is not
claimed. The JDK 17 install at
C:/Users/X1/AppData/Local/Temp/tunerostools/jdk17/jdk-17.0.21.1+2 is present,
but this gate is static and uses only bash, python and git, so no compilation
or device run is implied.

Reading record
--------------
Read for this task: AGENTS.md, CLAUDE.md, docs/ROADMAP.md and the scratch
copies docs_HERMES_EXECUTION_ROADMAP.md, docs_HERMES_INTERNAL_CRON.md and
docs_HERMES_WORK_LEDGER.md, plus the deterministic-qa-gates skill and its
byte-exact-fixtures reference, and the complete C8 MAP-05 and C5 MAP-04 diffs.
Full engineering-book compliance is NOT claimed.
