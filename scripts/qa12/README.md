QA-12 - deterministic DVR crash / partial-write recovery gate
=============================================================

Owned paths only: scripts/qa12/**, qa/fixtures/qa12/**, app/src/androidTest/**.
No production source, core, Gradle, manifest, docs, CI or shared status file is changed.

Audit boundary (read before building this gate)
----------------------------------------------
- DVR-01/02 own live InternalRecordingSink finalization and StorageCatalogPolicy fault decisions
  in JVM unit tests. QA-12 does not repeat those single-capture API checks.
- QA-04 owns scheduled-recording durability, 200-row keyset paging and exact-alarm rearm.
- QA-10 owns OnDeviceSelfTest event-line aggregation and its bounded shareable summary.
- QA-11 owns TunerBackup guide-state encode/decode and restore normalization.
- QA-12's distinct seam is restart-time reconciliation of persisted recording bytes against a
  crash-snapshot catalog: empty/truncated media, missing/duplicate/partial catalog rows, abrupt
  mid-write progress, bounded direct-to-disk journal output and secret-free diagnostics.

Commands (JDK 17; Kotlin 2.0.21 jars only from the local offline Gradle cache)
------------------------------------------------------------------------------
  bash scripts/qa12/run.sh generate    regenerate the pinned scratch fixtures byte-for-byte
  bash scripts/qa12/run.sh verify      host checks + RED controls + Kotlin mirror digest
  bash scripts/qa12/run.sh red-green   eleven on-disk RED mutants, then GREEN
  bash scripts/qa12/agp_compile.sh     offline AGP compile of the instrumented APK

The host gate is a single Java 17 source compiled with javac. It writes a bounded recovery
journal through a size-capped, row-capped, chunk-capped streaming writer into scratch. All derived
secret markers are computed at run time from a fixed seed; no provider URL, credential, PIN,
secret or recording-payload literal is written to fixtures, reports or stdout. Failures print
check ids, counts and digests only, with exception class names rather than messages.

Fixture dimensions: 96 ids, 108 physical catalog rows (96 strict, 12 truncated), 84 media files,
4096-byte per-file cap, 96 journal rows, 8192-byte journal cap, 512-byte write chunk. Derived
case counts: 12 completed, 24 partial/mid-write, 12 empty, 12 missing, 12 oversize, 12 duplicate,
12 invalid-row. Every cap and every special case is present in the committed fixture.

Instrumentation: app/src/androidTest declares only QA-12's runner. Sibling workers own sibling
instrumentation entries; the integrator is responsible for merging canonical manifests. The
shared assertion object has no Android imports and runs on the JVM before any device attempt.
Device execution is BLOCKED/UNVERIFIED without an attached authorized device or emulator; this
repository does not claim a device PASS from a compile alone.
