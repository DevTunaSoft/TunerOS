# QA-48 — bounded DVR capture storage and cancellation byte-copy gate

Owned paths only: `scripts/qa48/**`, `qa/fixtures/qa48/**`, `app/src/androidTest/**`.
No production source, core source, Gradle file, workflow, shared document or
`app/src/main` file is changed. No PR is opened and no merge is performed.

## Slice and audit

The gate exercises the bounded storage-safety contract of the real compiled
production units `core/StreamRecorder.kt::copy` and
`core/RecordingModels.kt::RecordingPolicy.remainingFreeBytes`:

- a copy refuses to start below the reserved free-space floor;
- cancellation and free-space changes are rechecked after a blocking read and
  again immediately before each write;
- a stream that returns zero bytes repeatedly is rejected instead of spinning;
- missing, zero, negative, exhausted and overdrawn capacity reports fail closed.

Audit of QA-01..QA-47: the 4.1 MB cross-branch QA gate corpus (all sibling
`scripts/qa*`, `qa/fixtures/qa*` and `app/src/androidTest` trees) has zero hits
for `StreamRecorder`, `LOW_STORAGE`, `minFreeBytes`, `freeBytes`,
`remainingFreeBytes`, `END_OF_STREAM` and `made no progress`. Exclusions:
QA-41 (recording outcome matrix), QA-42 (soak contract), QA-44 (timeshift ring
filesystem), QA-45 (source restoration), QA-46 (HTTP identity length), QA-47
(recording-worker shutdown journal), QA-17 (SAF destination selection) and
QA-32 (storage mapping and URI redaction). Core JVM tests exist for the units,
but no prior QA gate drives them from a byte-pinned fixture with negative
controls, bounds and a privacy scan.

## Fixture

Five byte-pinned LF files under `qa/fixtures/qa48/`: `dimensions.txt`,
`cases.tsv` (15 bounded cases), `corrupt.tsv` (8 pinned rejection codes),
`expected.txt` (golden projection) and `sha256.txt` (strict manifest).
Aggregate SHA-256: `591084c3b6e675c94e32811c4b15faa09311879930c84a727e7e6447a572b481`.

## Commands

    bash scripts/qa48/run.sh
    bash scripts/qa48/agp_compile.sh

`run.sh` compiles the real pure-Kotlin production units with the cached offline
K2 compiler, compiles the Java gate, then runs deterministic regeneration,
seven scratch-only RED controls with pinned FIRST codes, byte-identical restore
proof over all 15 committed inputs and a forced-recompile GREEN run under a
64 MiB heap. The instrumented sources compile in AGP; device execution is not
claimed without an authorized adb target or emulator.
