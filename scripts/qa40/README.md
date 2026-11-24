QA-40 - pinned-SHA integration-sequence gate (host-only)
========================================================

Owned paths only: scripts/qa40/**, qa/fixtures/qa40/**. No product source,
androidTest, Gradle, manifest, workflow, shared document, .hermes state or
sibling QA path is changed. No PR is opened and no merge is performed.

What is unique here (audit of QA-29..39 first)
----------------------------------------------
QA-29/30 are bounded Kotlin fixture gates for DVR final states and 206 partial
content. QA-31 pins C2/C3 DVR-03 + REL-01 needles. QA-32 covers MAP-04 seams and
CORE-49 URI redaction. QA-33 carries one MAP-04/MAP-05 hazard marker. QA-34 is
the dual-hazard gate over C2/C3/C5/C8 with QA-31/QA-33 cross-refs. QA-35 pins C7
(GUIDE-07) D-pad/recycled-row semantics, QA-36 pins C11 (HTTP-07) hygiene and
CORE-49, QA-37 pins C10 (FAV-06) series-rule replacement. QA-38 and QA-39 have
no gate files at their local branch heads (both sit at the common base), so no
check is inferred from them. QA-40 does not re-run any of those single-gate
checks: it verifies the eight exact heads, their ref-head drift, exact git blob
identity and added-file presence of each claimed fix artifact, and the integrity
plus continued force of QA-34's two markers. It is the integration sequence,
not another copy of a branch gate.

Pinned inputs (full SHA, never a moving branch name)
----------------------------------------------------
BASE     a523c2e43d198ee752f7c4f03f9f7853356ffb30  common base
PR 203   P203_C2    789f91b3280583238a333bda79f36f18939b77d2  C2 DVR-03
PR 206   P206_C3    672f6eb174dde6ef8592255d61a69f27227c2162  C3 REL-01
PR 210   P210_C5    9d27d9e25ec3a72a5d7bade03d513546d95a9b3a  C5 MAP-04
PR 213   P213_C8    fd4db647db31ce6b5bd9b1d193c356857e9c7122  C8 MAP-05
PR 214   P214_C7    62308fa1261362e8578d83796b8ddb8344ef120e  C7 GUIDE-07
PR 218   P218_C11   197520e59cbc1ede6e8234d17730247882731e79  C11 HTTP-07
PR 220   P220_C10   f57ab44ce83c9b281e43010c3e5b8dc968a189b6  C10 FAV-06
PR 221   P221_QA34  2f4aa76870dae0eb991492329132a8fdf39ea132  B42 QA-34
QA33     a584c39df00e825bf68ff9ebae039fbc5dd6e51e  pinned QA-33 marker

Checks (43 fixed expectations)
------------------------------
4 fixture SHA-256 digests; 10 distinct commit objects; 8 PR branch heads equal
their pins; 8 fix-artifact blob identities plus 8 added-file checks (16); QA-34
marker row count and text (2); QA-34 refs consistency over DBASE/GBASE/C2/C3/C5/
C8/QA33 (7); QA-33 marker presence; 3 fixed row-count expectations; no production
drift; output privacy; 2 bounded synthetic hazard markers. Object reads are
capped at 65,536 bytes / 4,096 lines each. Report is bounded to check lines, one
OBSERVED line and one SUMMARY line.

A hazard is never normalized into a green summary. Exit 0 PASS (no hazard),
1 FAIL, 3 MERGE-REVIEW-NEEDED (all checks pass and both markers in force). At the
pinned heads the truthful result is exit 3, checks=43, pass=43, fail=0, hazards=2.

Commands and exact observed status
----------------------------------
  cd <worktree>
  bash -n scripts/qa40/run.sh                                PASS
  python AST parse + compile of scripts/qa40/mutate.py        PASS
  cd qa/fixtures/qa40 && sha256sum -c sha256.txt             4/4 OK
  bash scripts/qa40/run.sh gate-only                          EXIT=3
      SUMMARY checks=43 pass=43 fail=0 first=NONE
              verdict=MERGE-REVIEW-NEEDED expected=43 hazards=2
              base=a523c2e4 qa34=2f4aa768
      HAZARD  MERGE-REVIEW-NEEDED  hazard=DVR03_REL01_MERGE_DEPENDENCY  (90 bytes)
      HAZARD  MERGE-REVIEW-NEEDED  hazard=MAP04_MAP05_SHARED_STORE      (86 bytes)
  bash scripts/qa40/run.sh verify                             EXIT=0
      RED   rc=1 first=FIX_BLOB_FIX_P220_C10 hits=1 markers=0
      RESTORED rc=3 markers=2 verdict=MERGE-REVIEW-NEEDED
      RESTORE 11/11 BYTE_IDENTICAL
      GREEN rc=3 markers=2 checks=43 pass=43 fail=0
      MUTATION PASS one-scratch-mutation-detected-and-restored-byte-identical

The single mutation rewrites one byte-needle in one scratch overlay of the
pinned C10 fix artifact; no repository file is written. The overlay is scoped to
one declared (ref, path) pair and a missing declared substitution fails closed.
All 11 scratch object copies are re-read from pinned git blobs after the probe.

Bounds and privacy
------------------
Only the pinned commits and their fixed paths are read. No network, emulator,
device, unbounded walk or fixture-content echo. The report carries check ids,
counts, short SHAs and hashes only. Report and fixtures are scanned for URL,
credential and secret patterns. All hazard names and marker text are synthetic;
no provider data, real media or credentials are used. Device execution is NOT
RUN and no on-device PASS is claimed.

Reading record
--------------
Read for this task: AGENTS.md, CLAUDE.md, docs/ROADMAP.md, the internal cron doc,
the scratch work-ledger copy, and the deterministic-qa-gates skill with its
byte-exact-fixtures reference. Full engineering-book compliance is NOT claimed.
