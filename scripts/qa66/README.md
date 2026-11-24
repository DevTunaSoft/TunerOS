# QA-66 — bounded hide-without-guide channel admission host gate

Base SHA: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`
Branch: `hermes/worker-c50-qa66`
Owned paths: `scripts/qa66/**`, `qa/fixtures/qa66/**`, `app/src/androidTest/**`.
No production, core, Gradle, manifest outside `app/src/androidTest`, workflow,
document, sibling QA path or `.hermes` file is modified. No PR is opened and no
merge is performed.

## Chosen slice

`core/ChannelVisibility.kt::filter` — the composition of trimmed, case-insensitive
name search with `hideWithoutGuide` admission against `GuideSnapshot.hasSchedule`:
passthrough identity, fail-closed null guide, schedule presence, composed query
plus hide, no-match and input immutability. The gate compiles the unmodified real
`ChannelVisibility.kt` and `Models.kt` with the offline Kotlin 2.0.21 compiler and
drives the compiled function; no production behaviour is copied into the gate.

## Cross-branch audit and NO_EQUIVALENT_GATE finding

Read in full first: `C:/Users/X1/TunerOS/CLAUDE.md` and the scratch `AGENTS.md`.

- refs scanned: 451 (local heads plus `refs/remotes/origin`, deduplicated)
- refs containing in-scope QA files: 133
- distinct gate blobs read: 868; deduplicated QA corpus: 7,611,656 bytes
- corpus SHA-256: `e9d6e480b5aa2892cc53fbc9e0d823dc3a8bd00086c2556f4afaf55b6f4b5b52`
- exclusions: `.gitattributes` (292 files, 10,582 bytes), non-gate paths outside
  `scripts/qa*`, `qa/fixtures/qa*` and `app/src/androidTest`, and duplicate blobs
  (deduplicated by git object id). `docs/**`, README, CLAUDE.md, AGENTS.md,
  `app/src/main/**`, `core/**`, Gradle, root manifest, workflows and `.hermes/**`
  were read for context or audited read-only and never modified.
- zero-hit keywords in the QA corpus: `qa66`, `QA-66`, `channelsWithoutSchedule`,
  `isDocumentOnRoot`, `currentProgramsAt`, `latestEnd`, `pollDelayMillis`,
  `statusOf`, `sharedChannelId`, `sharedWords`, `checkedUrl` (case-insensitive
  full-text scan). `hideWithoutGuide` has exactly one hit, in the QA-63 README
  where it is listed as a zero-hit keyword — no gate calls the seam.
- neighbouring gates audited: QA-02 uses `ChannelVisibility` only for 25k/250k
  import counts; QA-05 covers guide name search; QA-63 ranks doctor names;
  QA-13/44/62 own timeshift windows and segment lifecycle; QA-64 owns timeline
  width units. None evaluates hide-without-guide admission.

**Finding: NO_EQUIVALENT_GATE.** This is a distinct bounded quality slice.

## Files

- `scripts/qa66/Qa66Gate.java` — zero-dependency host gate (`generate` / `verify`)
- `scripts/qa66/mutate.py` — scratch-only single-needle production mutant helper
- `scripts/qa66/privacy_scan.py` — six-class privacy detector with self-test
- `scripts/qa66/run_qa66.sh` — bounded runner (RED → RESTORE → GREEN → PRIVACY)
- `scripts/qa66/agp_compile.sh` — offline AGP compile/assembly plus honest device probe
- `scripts/qa66/mirror_check.sh` and `Qa66MirrorMain.kt` — offline JVM driver for the
  androidTest mirror (compiled by K2, not by AGP)
- `qa/fixtures/qa66/` — `.gitattributes`, `dimensions.tsv`, `cases.tsv`, `sha256.txt`
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa66/` — Android-free mirror
  and bounded `Instrumentation` runner; `app/src/androidTest/AndroidManifest.xml`

## Bounded fixture

Seed `20261124`; 4 channels, 12 cases, maximum query length 32 characters.
`dimensions.tsv` 93 bytes, `cases.tsv` 409 bytes, `sha256.txt` 155 bytes.
Generation is deterministic and byte-identical: `verify` regenerates all three
files in memory and compares every byte.

Pinned SHA-256:

- `cases.tsv` `c0d9838e8490ae16bc4830bdda848b90fa7a3f94d837b216a8ad499a6ac97cea`
- `dimensions.tsv` `b1defe8d78b0bd5017de5e22eec710a5daa8313c15f679d4709e44fdd5317cf0`
- `sha256.txt` `fcfd3e6bb3041cd294d785f6d2372548f7e8c42015912f17e5564e79ee6ca73a`

## Negative controls (all must go RED with the pinned FIRST code)

| mutant | scratch mutation | pinned FIRST | observed |
|---|---|---|---|
| M1_TRIM | drop `query.trim()` | `QUERY_TRIM` | `QUERY_TRIM` rc=1 |
| M2_CASE | case-sensitive name match | `QUERY_CASE` | `QUERY_CASE` rc=1 |
| M3_NULL_GUIDE | null guide returns all channels | `NULL_GUIDE_FAIL_CLOSED` | `NULL_GUIDE_FAIL_CLOSED` rc=1 |
| M4_SCHEDULE | admit any non-null guide id | `SCHEDULE_ADMISSION` | `SCHEDULE_ADMISSION` rc=1 |
| M5_PASSTHROUGH | empty query returns empty list | `PASSTHROUGH_IDENTITY` | `PASSTHROUGH_IDENTITY` rc=1 |
| F6_FIXTURE | byte flip in scratch manifest | `BYTE_IDENTITY` | `BYTE_IDENTITY` rc=1 |

All mutations are applied to scratch copies only. Committed production sources and
fixtures were verified byte-identical before and after (RESTORE PASS, 5 files).

## Commands and exact observed status

- `bash -n scripts/qa66/run_qa66.sh` — PASS
- `bash -n scripts/qa66/agp_compile.sh` — PASS
- `bash -n scripts/qa66/mirror_check.sh` — PASS
- `python3 -m py_compile scripts/qa66/mutate.py scripts/qa66/privacy_scan.py` — PASS
- `bash scripts/qa66/run_qa66.sh` — exit 0
  - `BUILD PASS`; `RED PASS mutants=6`
  - `RESTORE PASS src=PASS fixture=PASS files=5`
  - `REGEN PASS rc=0 files=3`
  - `GREEN PASS checks=18 pass=18 fail=0 first=NONE thread_drift=0 heap=100663296` (`-Xmx96m`)
  - `PRIVACY PASS classes=6 selftest=PASS logs=8`
  - `VERDICT PASS red=6 green=18 checks=18`
- `bash scripts/qa66/mirror_check.sh` — exit 0, `MIRROR PASS checks=17 fail=0 rc=0`
  (K2 compile of production + mirror, then `-Xmx96m` JVM run of the mirror)
- `bash scripts/qa66/agp_compile.sh` — exit 0, final tree
  - `AGP PASS compileDebugAndroidTestKotlin assembleDebugAndroidTest`
  - `APK PASS bytes=25000`; `MANIFEST 1` (qa66 instrumentation entry)
  - `DEVICE BLOCKED no-authorized-adb-target`

## Privacy and bounds

The gate emits only case ids, family codes, pass/fail counts, heap size and thread
counts. No M3U/XMLTV URL, credential, PIN, secret, DRM key or recording payload is
read or written. `privacy_scan.py` self-test proves all six detectors fire on
synthetic scratch text, then scans all eight diagnostic logs: PASS. Threads are
sampled at gate entry and exit: zero drift. Heap maximum is 100,663,296 bytes
under `-Xmx96m`.

## Device status

The AGP compile and APK assembly are PASS on the final tree. Device execution is
**NOT RUN / UNVERIFIED**: the probe reported `DEVICE BLOCKED
no-authorized-adb-target`, so no emulator or device run and no device PASS is
claimed. The androidTest mirror is compiled by AGP and additionally exercised as
an Android-free JVM check (17/17 PASS); that is static/JVM evidence, not a device
result.
