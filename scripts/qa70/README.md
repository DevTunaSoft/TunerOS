# QA-70 — bounded capture-source range and key admission host gate

Owned paths only: `scripts/qa70/**`, `qa/fixtures/qa70/**`, `app/src/androidTest/**`.
No production, core, Gradle, workflow, shared document, root manifest or PR/merge is changed.

## Slice and cross-branch audit

The gate exercises the adapter-level bounded response contract of the real, unmodified
`app/src/main/java/com/devtunasoft/tuneros/AndroidDashCaptureSource.kt` and
`AndroidHlsCaptureSource.kt`:

- a DASH SegmentBase index range outside `(0, DashSegmentIndex.MAX_INDEX_BYTES]` is refused
  before a connection is opened;
- a range request answered with a non-206 2xx code is refused, and a non-2xx code becomes a
  structured `HttpStatusException`;
- a short range body is refused instead of returning zero-padded bytes;
- an HLS AES key body larger than 16 bytes is refused rather than truncated, while an exact
  or shorter body is returned unchanged;
- a 404 from a DASH segment request is mapped to `DashSegmentNotPublishedException`;
- every opened connection is disconnected, and repeated bounded parses stay inside the heap
  and thread-drift limits.

Audit scanned 459 local and remote refs, 1,016 distinct QA paths and 967 distinct blobs
(7,852,391 corpus bytes) across `scripts/qa*`, `qa/fixtures/qa*` and `app/src/androidTest`.
Zero hits: `qa70`, `AndroidDashCaptureSource`, `AndroidHlsCaptureSource`, `loadKey`,
`DASH index range`, `not honoured` and `key response is larger`. The single `loadRange`
hit is a fake throwing stub in QA-59, not a call to the real adapter. Exclusions: QA-68
(evidence cause-chain extraction), QA-69 (schedule-store claim/delete state machine),
QA-48 (`StreamRecorder.copy` storage safety), QA-22 (HLS IV/decrypt admission), QA-23
(`sidx` box admission), QA-26 (manifest parsing), QA-19/QA-46 (acquisition and body-length
verdict), QA-25 (catalog paging) and QA-59 (fake capture source). Finding:
NO_EQUIVALENT_GATE — no prior gate drives these adapter methods or their argument set.

## Fixture

`qa/fixtures/qa70/` holds 88 bounded cases across 11 families, eight named corrupt rows,
a dimensions file, the contract and fixture prose, LF-pinned attributes and a SHA-256
manifest. Every byte is regenerated from fixed constants by `Qa70Gate generate`; `verify`
never writes into the fixture directory. All names and bodies are invented scratch values.
No provider address, credential, personal identification number, protected-content key or
recording payload is stored or printed. A synthetic marker is derived at run time from the
seed and is never written to source or fixture.

## Commands

    export JAVA_HOME=C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2
    bash scripts/qa70/run.sh generate
    bash scripts/qa70/run.sh
    bash scripts/qa70/agp_compile.sh

The runner regenerates the fixture into scratch and compares aggregate digests, compiles the
real production sources offline with the cached Kotlin 2.0.21 compiler, runs eight
scratch-only negative controls with pinned FIRST codes, proves byte-identical restoration
of every scratch production and fixture copy, then runs the unchanged gate under a 96 MiB
heap with zero thread drift. The Android-free mirror runs on the JVM and compiles into the
instrumented APK. Device execution needs an authorized target and is reported as
BLOCKED / UNVERIFIED without one.

Pinned FIRST codes: BYTE_IDENTITY, MANIFEST_CONSISTENT, PINNED_DIGEST, RANGE_BOUNDS,
RANGE_STATUS, RANGE_SHORT, KEY_SIZE and PRIVACY_SCAN.

## Merge hazards

Sibling branches add their own `app/src/androidTest/AndroidManifest.xml` instrumentation
entry. This file contains only the `qa70` entry; merging sibling entries is an integrator
decision. No PR is opened and no merge is performed by this worker.
