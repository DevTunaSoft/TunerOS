QA-31 - host-only deterministic gate for the DVR-03 + REL-01 fix combination
============================================================================

Owned paths only: scripts/qa31/**, qa/fixtures/qa31/**. No product source,
Gradle, manifest, core source, androidTest, workflow or shared document is
changed. No PR is opened or merged by this worker.

Pinned inputs (full SHA, never a moving branch name)
----------------------------------------------------
BASE c6153ed1197c7b4c7b03f44126baa6d3f71ec819
C2   789f91b3280583238a333bda79f36f18939b77d2  PR #203, DVR-03 exact rearm
C3   672f6eb174dde6ef8592255d61a69f27227c2162  PR #206, REL-01 upgrade rearm

Audit boundary
--------------
QA-29 (worker-b38) gates DVR terminal-state transitions only; a cross-branch
git grep over every worker-b* QA script/fixture tree returned zero hits for
MY_PACKAGE_REPLACED, UpgradeRearmReceiver, rearmInProgress and exactRearmDone.
QA-30 (worker-b39) has no committed files in any local or remote ref at audit
time and owns a disjoint path. QA-31 therefore covers a distinct invariant:
the combination of the two unmerged fixes, checked statically at pinned SHAs.

What is verified
----------------
- C3 manifest: exactly one non-exported receiver for
  android.intent.action.MY_PACKAGE_REPLACED, named .UpgradeRearmReceiver,
  with exactly one action and no boot action in that receiver block.
- C3 receiver class UpgradeRearmReceiver (file RecordingRearmReceiver.kt):
  exactly one RecordingScheduler.rearmPending( dispatch, action guard before
  the dispatch, application context and goAsync/finish pair.
- C2 RecordingScheduler: both rearmInProgress and exactRearmDone guards, the
  compareAndSet, the done check, the guarded done set and in-progress release.
- Neither fix is silently dropped: BASE has none of the markers, C2 changes
  only the scheduler, C3 changes only the manifest plus receiver, and C3 has
  no C2 guards. A byte-pinned fixture defines every expected string and count.

Commands
--------
  bash -n scripts/qa31/run.sh
  python3 -m py_compile scripts/qa31/mutate.py
  bash scripts/qa31/run.sh gate-only    pinned git objects, GREEN checks only
  bash scripts/qa31/run.sh verify       scratch mutation RED, restore, GREEN

Bound and privacy
-----------------
Only three fixed git objects are read, each capped at 65536 bytes / 2000
lines. No emulator, no network, no unbounded file walk. The report contains
check ids, counts and hashes only; report and fixtures are scanned for URL,
credential and secret patterns. The mutation substitutes exactly one declared
input (C3 receiver) from a native-path mktemp scratch directory; all other
inputs still come from pinned git objects. It restores byte-identical copies,
and production paths are checked for drift. Device execution is not claimed:
no on-device PASS is implied.

Reading record
--------------
Read for this task: AGENTS.md, CLAUDE.md, docs_HERMES_EXECUTION_ROADMAP.md,
docs_HERMES_INTERNAL_CRON.md, docs_HERMES_WORK_LEDGER.md from the shared
scratch doc cache, plus the deterministic-qa-gates skill and the complete C2
and C3 diffs. Full engineering-book compliance is NOT claimed.
