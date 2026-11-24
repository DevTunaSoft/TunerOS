# QA-44 — bounded timeshift ring filesystem seam

Deterministic, offline, zero-new-dependency host gate over the **real production**
`app/src/main/java/com/devtunasoft/tuneros/AndroidTimeshiftRing.kt`. No product, core, Gradle,
workflow, manifest outside `app/src/androidTest`, shared document or sibling QA path is changed.
Base worktree SHA: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`.

## Slice and audit

All 42 sibling QA branches rooted at the same base were catalogued before authoring. QA-13 and
QA-42 own core timeshift window/retention models; QA-17 owns SAF destination selection; QA-29
owns DVR terminal states; QA-43 owns source hygiene. A cross-branch grep over every sibling
`scripts/qa*`, `qa/fixtures/qa*`, and `app/src/androidTest` tree returned **zero** hits for
`AndroidTimeshiftRing`, `replacePlaylist`, `TEMPORARY_PLAYLIST_NAME` or
`timeshift file name leaves the ring`. QA-44 therefore owns the distinct **app-side filesystem
seam**: strict ring-directory containment, fail-closed corrupt names, atomic playlist publication
(temp write + rename), surfaced delete/rename failures, and bounded resource use. It does not
re-run or copy a sibling gate.

## Files

- `run.sh` — generate / RED + RESTORE + GREEN / gate-only orchestration.
- `Qa44HostMain.kt` — host entry point; captures and scans every emitted diagnostic line.
- `Qa44Contract.kt` (under `app/src/androidTest/.../qa44/`) — shared framework-free contract,
  compiled into both the host gate and the instrumented mirror.
- `stubs/.../TimeshiftRing.kt` — host-only signature mirror of the production core interface.
- `mutate.py` — nine zero-dependency negative controls; writes only scratch copies.
- `agp_compile.sh` — offline AGP compile/package attempt and honest device probe.

## Fixture and bounds

`qa/fixtures/qa44/` holds five pinned files plus README and `.gitattributes`. Fixed seed
`20261124`; seven families x eight cases = 56 rows; 3,641 fixture bytes; strict headers and field
counts; per-file SHA-256 manifest; production source digest pin. The host regenerates all five
bytes into scratch and compares them to the committed fixture before any semantic check runs.

Ring operations run under `-Xmx96m` in a scratch tree: eight creates, sixteen deletes, eight
playlist replacements, twenty-four expected refusals (eight corrupt names, eight non-empty
directories that cannot be deleted, eight playlist targets that cannot be renamed over). A
runtime-derived marker is written into a real playlist and must never appear in diagnostics.
Heap use and thread drift are measured, not inferred from code.

## Observed results (JDK 17.0.21.1+2, Kotlin 2.0.21 offline jars)

```
COMPILE   PASS  kotlin2.0.21+jvm17
BYTE_IDENTITY PASS committed-fixture-equals-regenerated-bytes
RED       PASS  sha-drift|MANIFEST_MISMATCH, byte-drift|BYTE_IDENTITY,
                header-drop|HEADER_STRICT, field-extra|FIELD_COUNT,
                family-unknown|FAMILY_CODE, expect-flip|CORRUPT_EXPECTATION,
                cap-loosen|INT_BOUNDS, privacy-leak|DIAG_LEAK,
                atomicity-marker|ATOMICITY_MARKER
COUNTS    created=8 deleted=16 replaced=8 refused=24
HEAP      used≈1.3 MiB, limit=75,497,472 bytes; thread drift <= 2
SUMMARY   checks=81 pass=81 fail=0 first=NONE
DIAG_SCAN PASS  no address, credential, PIN or payload marker in emitted lines
RESTORE   PASS  fixture and production digests unchanged by all mutants
```

Offline AGP `:app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest` **PASS** with a
local Android 35 SDK; test APK assembled and the QA-44 instrumentation entry verified in its
manifest. Instrumentation **NOT RUN**: no authorized adb target or emulator is attached, so
device execution is **BLOCKED / UNVERIFIED**. A host gate is not evidence of on-device behavior.
