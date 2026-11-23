# QA-06 — large Classic Cable EPG scale gate

Deterministic, zero-dependency host gate for the compiled guide's scale contract. It complements
QA-01 (small compiled layout, D-pad/OK) and QA-05 (small-fixture search and hide-no-EPG) without
repeating their cases. No product source, core source, Gradle file, workflow or shared document is
changed.

## Fixture contract

`scripts/qa06/Qa06LargeEpgGate.java` generates a 14,650,331-byte scratch fixture with:

- 25,000 channels `c-00000` … `c-24999`, each with a matching guide id and a non-URL
  `scratch-loopback:qa06/…` reference;
- 250,000 programmes on a fixed 30-minute grid (BASE = 1,800,000,000);
- a three-hour guide window from 1,800,003,600 to 1,800,014,400 (six 30-minute slots);
- a 24-row paged guide model with four boundary `PAGE` records and at most eight cells per row.

The generator is byte-identical across runs. The committed `manifest.txt` pins the counts, window,
page size, cell bound and SHA-256 of those bytes. Verifier checks cover stable channel and
programme ordering, exact record counts, bounded page indices and row counts, and contiguous window
coverage (no clipped visible rows) on 265 sampled rows. The report is scanned for URL-like and
credential-like tokens before it is written.

## Negative controls

Each control is a deliberate in-memory mutation of the canonical fixture and must be rejected by a
named check:

| Mutation | Required rejection |
|---|---|
| `ordering-altered` | `CHANNEL_ORDER` |
| `clipped-visible-row` | `ROW_NOT_CONTIGUOUS` |
| `page-count-overflow` | `ROW_COUNT_BOUND` |
| `non-loopback-reference` | `PRIVACY_REF` |

The gate also writes each mutated fixture and runs the standalone verifier on it; a mutation that
passes verification is a gate FAIL. Failure diagnostics name only the check and record number, so
the injected reference never appears in a report or diagnostic.

## Files

- `scripts/qa06/Qa06LargeEpgGate.java` — generator, verifier and four negative controls.
- `qa/fixtures/qa06/manifest.txt` — canonical fixture manifest.
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa06/Qa06LargeEpgAssertions.kt` — the same
  seven assertions, runnable on a JVM and on-device.
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa06/Qa06LargeEpgInstrumentation.kt` —
  platform `Instrumentation` runner; no JUnit/AndroidX dependency.
- `app/src/androidTest/AndroidManifest.xml` — runner registration in the test APK only.
- `scripts/qa06_large_epg_gate.sh` — bounded runner (generator, verifier, mutations, source gate,
  optional AGP compile, device probe).

## Evidence

On this worktree the full runner exited 0 with one Android 35 TV AVD (`emulator-5554`,
`sdk_gphone64_x86_64`, 1920x1080) attached:

- generator: 14,650,331 bytes, SHA-256 `873347fe676274dc5e2064c572d7b256e5041cdd174cd97d3619dc62b81d57ce`, byte-identical across runs;
- JVM gate: 9,505,563 checks, 0 failures (positive 1,901,111; four negative controls 7,604,444);
- four on-disk mutated fixtures each FAILED standalone verification with the expected named check;
- Kotlin assertions: 7 PASS / 0 FAIL on JVM and on device;
- offline AGP compile and test APK package: PASS, 23,254 bytes;
- device instrumentation: 7 PASS / 0 FAIL, `qa06_failures=0`.

Device evidence is from a scratch emulator, not physical TV hardware. It proves the bounded
assertions on that target only; it does not claim physical remote or provider acceptance.

## Status vocabulary

Host verification and compilation are recorded from real command output in the worker heartbeat.
Device execution is `BLOCKED / NOT RUN` when `adb devices` has no authorized target; compiled test
APK evidence is never presented as device acceptance.
