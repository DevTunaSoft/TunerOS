QA-21 - deterministic self-test report-publication gate
========================================================

Owned paths only: scripts/qa21/**, qa/fixtures/qa21/**, app/src/androidTest/**.
No production, core, Gradle, workflow, shared document or production manifest file is changed.
No PR is opened and no merge is performed by this worker.

Audit boundary (read first)
---------------------------
- QA-10 owns OnDeviceSelfTest event-line aggregation and the bounded shareable summary.
- QA-16 owns cancellable import staging, atomic guide publication and rollback.
- QA-18 owns corrupt doctor-evidence envelope ingestion before reports are produced.
- QA-09 owns doctor shareable-report privacy; QA-12 owns DVR crash/partial-write recovery.
- No audited gate covers the run-marker / pending-report / atomic-publication lifecycle in
  app/src/main/java/com/devtunasoft/tuneros/OnDeviceSelfTest.kt lines 137-147 and 637-644:
  interrupted previous-run classification, report bound enforcement, fail-closed pending
  document parsing, rename-before-marker-delete ordering and secret-free diagnostics.
QA-21 owns that gap. It is a scratch-state contract with a compiled Kotlin mirror; it does
not claim a production-code PASS and does not execute on a device.

Fixture contract
----------------
qa/fixtures/qa21/.gitattributes pins LF bytes. contract.txt, dimensions.tsv, runs.tsv,
corrupt.hex and sha256.txt total 16,949 bytes. The generator uses fixed constants (seed
20261123) and regenerates every byte identically. The pinned runs.tsv mirror digest is
785c0d4daa352bce671f5736333ee4c500c67d1b898e693249313add188b0512.

Dimensions: 64 bounded publication rows, 32-line / 4096-byte report caps, 6 corrupt pending
variants, 6 repeated parse cycles, thread cap 8, heap cap 96 MiB. One row reaches each cap.
The sensitive marker is derived at run time from a fixed seed and appears in no source,
fixture, report or heartbeat.

Commands (JDK 17, offline, no new dependency)
---------------------------------------------
  bash scripts/qa21/run.sh generate    regenerate the pinned scratch fixtures byte-for-byte
  bash scripts/qa21/run.sh verify      RED mutants, RESTORE proof, GREEN gate, JVM mirror
  bash scripts/qa21/run.sh red-green   alias for verify
  bash scripts/qa21/run.sh gate-only   GREEN gate without mutation controls
  bash scripts/qa21/agp_compile.sh     offline AGP compile/package attempt when an SDK exists

What runs
---------
The single framework-free Qa21Contract.kt source under app/src/androidTest/java is compiled
offline with Kotlin 2.0.21 into the host gate and into the instrumented APK. The host gate
regenerates every fixture byte, strict-parses the committed files, simulates each publication
decision, rejects each corrupt pending document with a pinned reason, scans diagnostics for
provider-like and sensitive markers, and samples heap/threads across six cycles under -Xmx96m.

RED controls
------------
Ten on-disk mutants must each exit nonzero with a pinned FIRST code: byte-drift (BYTE_IDENTITY),
sha-drift (MANIFEST_MISMATCH), header-missing (HEADER_STRICT), field-count (FIELD_COUNT),
int-bounds (INT_BOUNDS), enum-unknown (ENUM_STRICT), corrupt-accept (CORRUPT_REJECT),
outcome-mismatch (OUTCOME_MISMATCH), cap-shrink (CAP_BINDING), leak-inject (PRIVACY_LEAK).
Mutant manifests are rewritten before verification so digest drift cannot mask a semantic
rejection; the fixture tree hash must be byte-identical before and after the RED run.

Limitations
-----------
Offline AGP compilation proves source and packaging, not device behaviour. Device execution
is BLOCKED/UNVERIFIED without an attached authorized adb/emulator target. No production
recording, source, database or external media was touched.
