QA-16 - deterministic cancellable guide-import staging gate
============================================================

Owned paths only: scripts/qa16/**, qa/fixtures/qa16/**, app/src/androidTest/**.
No product source, core source, Gradle file, workflow, shared manifest or status document is changed.
No PR is opened and no merge is performed by this worker.

Audit boundary (read before building this gate)
------------------------------------------------
- QA-02/QA-15 cover large one-shot and repeated EPG import plus retention under a bounded heap.
- QA-06 covers large EPG record/page/row structure; QA-05 covers small-guide search and filtering.
- QA-11 covers backup encode/decode and restore; QA-12 covers DVR crash/partial-write recovery.
- QA-13 covers timeshift live windows; QA-14 covers reminder scheduling; QA-04 covers alarm rearm.
- No audited gate covers cancellation *before* an import commit, atomic publication of a new
  guide snapshot, or byte-identical rollback of the previously published snapshot.
QA-16 owns that gap. Its model is a scratch-state contract with a compiled Kotlin mirror; it does
not claim to have executed the production importer on a device.

Fixture contract
----------------
qa/fixtures/qa16/ holds contract.txt, dimensions.tsv, chunks.tsv, attempts.tsv, corrupt.tsv and
sha256.txt (375,551 bytes total). The generator uses a fixed seed and regenerates every byte
identically. The pinned snapshot digest is
71728993e3d736da3fba46137e02425af8e1d80f9758879f824d5d90748f6491.

Dimensions: 192 chunks x 24 rows = 4,608 scratch rows, 96 scratch channel ids, 1,800-second
slots on a fixed epoch grid, 24-row staging buffer, 16,384-byte journal cap, 48 MiB heap cap,
8 import attempts and 10 named corrupt rows. Every title and reference is a derived scratch
token. No feed address, access marker, PIN, DRM key, recording payload or real guide data is
stored. The leak marker used by the privacy control is derived at run time and never printed.

Checks
------
GREEN host gate: 31 checks, 0 failures. Byte identity, digest pins, dimensions, strict headers,
row and sequence bounds, snapshot digest, derived-marker pin, attempt table, fail-closed corrupt
parse, staging/journal/heap/thread bounds, thread join, five cancellations with no publication,
atomic commit, byte-identical rollback after a post-publish cancellation, repeat-identical commit,
six in-memory negative controls, privacy scan and report bound.

The importer runs one daemon worker per attempt, joins it, samples thread count and heap after
each attempt, and writes a size-capped journal into scratch. Cancellation clears staging before
commit; the published snapshot is a single atomic assignment. Heap is measured while the JVM runs
with -Xmx64m; peak was about 36 MiB against the 48 MiB cap, and thread count went from 1 to 2.

Commands (JDK 17, no network, no added dependency)
---------------------------------------------------
  bash scripts/qa16/run.sh generate     regenerate the pinned fixture bytes
  bash scripts/qa16/run.sh verify       RED controls, byte-identical restore, GREEN gate, mirror
  bash scripts/qa16/run.sh red-green    alias for verify
  bash scripts/qa16/run.sh gate-only    canonical GREEN gate without mutants
  bash scripts/qa16/agp_compile.sh      offline AGP test-APK compile/package attempt

RED controls
------------
Nine on-disk fixture mutants each failed with its pinned first check: byte flip, bad header,
row count, sequence gap, derived-marker leak, snapshot drift, cancel changed to publish,
corrupt-case acceptance and dimension drift. Five scratch source mutants each failed with the
pinned first check: staging cap, journal cap, snapshot compare, cancel guard and privacy guard.
After all 14 mutants the committed fixture and source hashes were byte-identical to the
pre-mutant aggregate SHA-256.

Instrumentation and device status
---------------------------------
app/src/androidTest declares only QA-16's runner; sibling workers own their own entries and the
integrator merges canonical manifests. Qa16Assertions.kt has no Android imports and ran on the JVM
with 15 checks passing. The offline AGP compile of compileDebugAndroidTestKotlin and
assembleDebugAndroidTest PASSed and the test APK contains the QA-16 instrumentation entry.

A compiled test APK is not device evidence. No authorized adb target or emulator was used, so
on-device instrumentation execution is BLOCKED / NOT RUN / UNVERIFIED. No device PASS is claimed.
