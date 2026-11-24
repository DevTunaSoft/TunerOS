# QA-62 — bounded timeshift segment-file lifecycle gate

Deterministic, zero-new-dependency host gate over the **real production**
`core/TimeshiftCapture.kt` segment-file lifecycle. No product, core, Gradle, workflow, shared
document or sibling QA path is changed. Base worktree SHA:
`a523c2e43d198ee752f7c4f03f9f7853356ffb30`.

## Slice and audit

A cross-branch corpus over 443 local and remote refs (126 refs carrying QA paths, 1,016 distinct
blobs, 7,426,192 QA bytes and 1,439,075 JVM-test bytes) returned zero hits for `onSegmentComplete`,
`openNextFile`, `oversized segment`, `finish idempotent`, `segment lifecycle`, `segment file
deletion`, `pin-attempt` and `line-budget`. The only sibling mention of `TimeshiftCapture` is a
QA-42 README sentence saying that gate does *not* cover it.

Excluded as equivalent or adjacent: QA-13 (timeshift window state and seek bounds), QA-44
(app-side ring filesystem and atomic playlist publication), QA-42 (combined soak), QA-20
(parental runtime decisions), QA-11/QA-38 (backup/restore), QA-05 (guide search), QA-01 (guide
layout), QA-30 (HTTP range), QA-47 (shutdown journal) and QA-48 (storage byte copy).

Finding: **NO_EQUIVALENT_GATE**. QA-62 owns the distinct orchestration seam: one file per
completed segment, eviction deletes only evicted files, an oversized segment is refused without a
playlist gap, completion opens the next file, each completion publishes a playlist, and `finish`
is idempotent and deletes the partially written file.

## Files

- `run.sh` — generate / RED + RESTORE + GREEN / gate-only orchestration, bounded to `-Xmx96m`.
- `Qa62HostMain.kt` — host entry point; compiles and drives the real `TimeshiftCapture` and
  `TimeshiftWindow` with a fake in-memory ring.
- `stubs/HlsSegmentListener.kt` — host-only signature mirror of the interface in
  `HlsCaptureEngine.kt`; the host main asserts the production signature in a check.
- `mutate.py` — 14 zero-dependency negative controls; writes only scratch copies.
- `privacy_scan.py` — external scan of fixtures, prose and manifest.
- `agp_compile.sh` — offline AGP compile/package attempt, no device invocation.
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa62/` — framework-free shared contract and
  instrumented mirror in a unique package.

## Fixture contract

`qa/fixtures/qa62` holds `dimensions.tsv`, `events.tsv` (24 rows in six families), `corrupt.tsv`
(six hex-encoded invalid rows), `sha256.txt`, and `.gitattributes` pinning line endings. All bytes
come from fixed constants; `verify` regenerates and compares every file. Identifiers are invented
scratch tokens. No feed address, account, personal identification number, DRM marker or recording
payload is stored. The leak marker is derived at run time from a constant seed.

Pinned data digests (SHA-256):

- `dimensions.tsv` `ae18e823d1dd537d9181aeaa2358627107b4c79a8254e5122bf5f958dc60b1b3`
- `events.tsv` `3cb4a321291c8d1f8f7dce0a42f58e6d99a5a250afdd739c3c89ebbcce2be7a8`
- `corrupt.tsv` `da709f539f18f2ac0d1d654c8dd01c0d1e9c6ff21bb5fc7657a67ac7033548a1`
- fixture tree `171b66cdc8174131778b645985d492b544e978da3ee863032fef03b1258180d8`
- total fixture bytes `2013`

Bounds actually exercised: cap of three retained segments, byte budget of 2,500, six nonempty
families, six corrupt variants, 70 journal lines in the full replay, and one oversized-segment
scenario. Every cap binds.

## Checks and negative controls

Seventeen positive checks: byte identity, manifest, pinned digest, strict headers, row bounds,
cap binding, fixture privacy, fail-closed corrupt variants, count binding, oversize refusal,
finish idempotence, next-file opening, playlist publication, open-file deletion on finish, full
journal against an independent reference, production source contract, and report privacy.

Fourteen scratch-only mutations must each exit nonzero with their own pinned first code:
`BYTE_IDENTITY`, `MANIFEST_CONSISTENT`, `PINNED_DIGEST`, `CAP_BINDING`, `HEADER_STRICT`,
`ROW_BOUNDS`, `CORRUPT_FAIL_CLOSED`, `PRIVACY_SCAN`, `CONTRACT_JOURNAL`, `OVERSIZE_REJECT`,
`FINISH_IDEMPOTENT`, `NEXT_FILE_OPEN`, `PLAYLIST_PUBLISH`, `FINISH_OPEN_DELETE`. The committed
fixture and production bytes are hashed before and after the whole RED batch.

## Device execution

A compiled test APK is not a device PASS. No authorized adb or emulator target is attached, so
instrumented execution is BLOCKED / NOT RUN and physical-device acceptance is UNVERIFIED.
