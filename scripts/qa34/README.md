QA-34 - host-only dual-hazard gate for the DVR and MAP merge chains
===================================================================

Owned paths only: scripts/qa34/**, qa/fixtures/qa34/**. No product source,
Gradle, manifest, androidTest, workflow, shared document or sibling QA path is
changed by this worker. No PR is opened and no merge is performed.

Pinned inputs (full SHA, never a moving branch name)
----------------------------------------------------
DBASE/GBASE a523c2e43d198ee752f7c4f03f9f7853356ffb30  common merge base
C2          789f91b3280583238a333bda79f36f18939b77d2  DVR-03 exact rearm guards
C3          672f6eb174dde6ef8592255d61a69f27227c2162  REL-01 upgrade receiver
C5          9d27d9e25ec3a72a5d7bade03d513546d95a9b3a  MAP-04 store seams
C8          fd4db647db31ce6b5bd9b1d193c356857e9c7122  MAP-05 retain pruning
QA31        fcabc641cb2444219dbbedfbaaf235df4668090a  pinned QA-31 gate
QA33        a584c39df00e825bf68ff9ebae039fbc5dd6e51e  pinned QA-33 marker gate

Audit boundary (read before building this gate)
-----------------------------------------------
QA-29 (b38) checks bounded DVR final-state transitions; QA-30 (b39) checks
bounded 206 partial-content admission; QA-31 (b40) is a static needle gate over
the DVR-03 + REL-01 fix combination but emits no hazard marker; QA-32 (b41)
checks MAP-04 storage seams and CORE-49 URI redaction; QA-33 (c9) emits one
marker for the MAP-04/MAP-05 shared-store hazard. A cross-ref grep over all
worker QA script trees found MERGE-REVIEW-NEEDED only in QA-33. QA-34 is the
combined gate: it cross-checks the pinned QA-31 and QA-33 identities and
requires two distinct markers in one bounded report, so neither hazard can be
silently reduced to a PASS. It duplicates no sibling gate.

What is verified
----------------
DVR chain: C3 adds a single non-exported MY_PACKAGE_REPLACED receiver whose
guard precedes exactly one RecordingScheduler.rearmPending dispatch; C2 adds
the rearmInProgress CAS, exactRearmDone check, guarded done set and release;
C3's scheduler and the common base have none of those guards, and C2's manifest
has no upgrade receiver. The fixes sit on different production paths from the
same merge base, yet C3 depends on C2's idempotence guard. That is a recorded
merge dependency, not a combination PASS.

MAP chain: C5 and C8 share the same merge base and both rewrite
GuideMappingStore.kt; C5 has the MAP-04 Storage seams and no retain, C8 has the
MAP-05 retain signature, fail-closed empty guard and bounded allocation, and no
C5 seams. The QA-33 marker and both QA-33 refs are still present at the pinned
QA-33 commit.

Verdict and exit codes
----------------------
Every check must pass and both hazard conditions must hold. The report then
contains exactly two synthetic, bounded marker lines (marker text comes from
the pinned QA-33 marker fixture):

  HAZARD  MERGE-REVIEW-NEEDED  hazard=DVR03_REL01_MERGE_DEPENDENCY  ...
  HAZARD  MERGE-REVIEW-NEEDED  hazard=MAP04_MAP05_SHARED_STORE      ...

Exit 0 PASS (no hazard), 1 FAIL (any check or hazard missing), 3
MERGE-REVIEW-NEEDED (all checks pass, both hazards present). At the pinned
SHAs the truthful exit is 3; a false PASS is not emitted.

Commands and exact observed status
----------------------------------
  bash -n scripts/qa34/run.sh                                  PASS
  python AST parse + compile of scripts/qa34/mutate.py         PASS
  sha256sum -c qa/fixtures/qa34/sha256.txt                     3/3 OK
  bash scripts/qa34/run.sh gate-only                           EXIT=3
      SUMMARY checks=46 pass=46 fail=0 first=NONE
              verdict=MERGE-REVIEW-NEEDED expected-total=46
              markers=2 (one per hazard, 100 and 96 bytes)
  bash scripts/qa34/run.sh verify                              EXIT=0
      RED dvr:  rc=1 fail=5 first=D_C3_REC_DISPATCH, DVR marker=0
      RED map:  rc=1 fail=3 first=M_C8_FAIL_CLOSED,  MAP marker=0
      RESTORED (both): rc=3, 46/46, markers=2, 13/13 scratch files BYTE_IDENTICAL
      GREEN:    rc=3, 46/46, markers=2, verdict=MERGE-REVIEW-NEEDED
      MUTATION PASS: two single-scratch mutations detected and restored

Each mutation changes one line in one scratch overlay: the C3 receiver dispatch
and the C8 fail-closed guard. The overlay is scoped to exactly one declared
(ref, path) pair and a missing declared substitution fails closed. No
repository or production file is written by the mutator.

Bounds and privacy
------------------
Only the eight pinned commits and their fixed paths are read, capped at 65,536
bytes / 2,000 lines each (MainActivity at 262,144 / 4,096). No network,
emulator, device, unbounded walk or fixture-content echo. The report contains
check ids, counts, line numbers, short SHAs and hashes only. Report and
fixtures are scanned for URL, credential and secret patterns. All markers and
names are synthetic; no provider data, real media or credentials are used.
Device execution is NOT RUN and no on-device PASS is claimed. The JDK 17
install is present, but this gate is static and uses only bash, python and git.

Reading record
--------------
Read for this task: AGENTS.md, CLAUDE.md, docs/ROADMAP.md, the scratch copies
docs_HERMES_EXECUTION_ROADMAP.md, docs_HERMES_INTERNAL_CRON.md and
docs_HERMES_WORK_LEDGER.md, the deterministic-qa-gates skill with its
byte-exact-fixtures and QA-worker references, and the complete diffs of the
four pinned commits. Full engineering-book compliance is NOT claimed.
