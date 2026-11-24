QA-41 - deterministic offline recording-outcome matrix gate
===========================================================

Owned paths only: scripts/qa41/**, qa/fixtures/dvr-outcome/**, and new tests under
app/src/test/java/com/devtunasoft/tuneros/qa41/**. No product source, Gradle file,
manifest, shared script, other QA path, document or .hermes state is changed by
the commit. No PR is opened and no merge is performed.

Audit (read-only, not duplicated)
---------------------------------
No gate for this matrix existed. hermes/worker-c14-gate41 is a focus41 branch,
not a QA-41 recording gate. Read-only coverage audited: A4 DVR-01/02
(DvrOutcomePolicy, StorageCatalogPolicy, sink/finalization regressions), C18
DVR-05 teardown ownership, C23 DVR-06 cancellation epoch, C17 STOR-07 write
accounting, C16 SAF-06 SAF grant loss, and the scripts/qa06..qa40 gates. Those
are JVM/branch checks on unmerged production code; this gate is an independent
fixture-driven contract evaluated on the host, plus an app JVM test over the
same fixture. It does not copy or modify their files.

Matrix
------
20 cases in seven families: completed=2, zero-byte=3, cancelled=3,
storage-fault=6, double-finalize=2, epoch-lost-stop=4. Expected terminal states:
COMPLETED=5, FAILED=5, PARTIAL=3, CANCELLED=7. The epoch family carries
shared_cancelled=0 with a retained cancel epoch, so cancellation-epoch-lost-STOP
is exercised explicitly. Every row is checked against the policy decided from
its own fields: cancellation wins, sink bytes are authoritative, zero-byte
COMPLETED becomes FAILED, storage faults never promote to COMPLETED, a second
finalization is a no-op, and a STOP stays terminal across a newer START.

Commands and exact observed status (base d72e6239)
--------------------------------------------------
  bash -n scripts/qa41/run.sh                                   PASS
  python AST parse of Qa41Gate.py and mutate.py                 PASS
  bash scripts/qa41/run.sh gate                                 EXIT=0
      SUMMARY checks=41 pass=41 fail=0 first=NONE verdict=PASS
      COUNTS cases=20 markers=7 states=COMPLETED=5,FAILED=5,PARTIAL=3,CANCELLED=7
  bash scripts/qa41/run.sh                                      EXIT=0
      RED    rc=1 first=POLICY_MARKER fail=1 checks=41
      GREEN  rc=0 pass=41 checks=41 fail=0 cases=20
      MUTATION PASS one-policy-marker-mutant-detected-and-committed-fixtures-untouched
  export ANDROID_HOME=C:/Android/Sdk JAVA_HOME=<jdk17>
  ./gradlew :app:testDebugUnitTest --offline --rerun-tasks        EXIT=0
      BUILD SUCCESSFUL in 1m 14s, 24 tasks executed
      JUnit XML: suites=1 tests=5 failures=0 errors=0 skipped=0
      suite com.devtunasoft.tuneros.qa41.DvrOutcomeMatrixTest tests=5

The single mutation rewrites stop_epoch_persists_across_start from 1 to 0 in a
scratch copy of markers.tsv only. Mutant mode replaces the pinned-byte check
with a scope check so the named semantic failure is the first failure; the
committed fixtures are never written and the following GREEN run re-verifies
their pinned SHA-256 digests.

Bounds and honesty
------------------
Fixtures are bounded (<=64 KiB, <=512 lines per file), parsed strictly, and
reject CRLF, bad headers, wrong column counts, non-numeric fields, duplicate
ids and out-of-range flags. The report carries check ids, counts and hashes
only: no URLs, credentials, payload bytes or fixture prose. All names are
synthetic; no real recording, media, database or provider is touched. Device
execution is NOT RUN and no on-device PASS is claimed.
