# QA-60 — bounded AbortableSlot cancellation and late-registration gate

Base SHA: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`.
Branch: `hermes/worker-c44-qa60`. Owned paths only:
`scripts/qa60/**`, `qa/fixtures/qa60/**`, `app/src/androidTest/**`.
No production source, core source, Gradle file, workflow, shared document or sibling QA path is edited.
No PR is opened and no merge is performed by this worker.

## Chosen slice

The gate drives the real compiled `core/src/main/kotlin/com/devtunasoft/tuneros/core/AbortableSlot.kt`
through its public `register`, `release`, `abortCurrent` and `reset` API. The bounded contract covers:
late registration after abort (rejected and aborted immediately), identity-only stale release,
idempotent abort, reset clearing both abort state and the registered value, sticky abort state until
reset, double registration replacing the single current value, a latch-based bounded probe proving
an abort callback does not hold the slot lock, fail-closed corrupt input, and privacy/resource bounds.
It does not reimplement cancellation policy: every verdict comes from the production class.

## Audit and duplicate exclusion

Cross-branch audit performed before authoring:
- refs scanned: 439 (`git for-each-ref` over local heads and `origin` refs);
- paths scanned: `scripts/qa*/**`, `qa/fixtures/qa*/**`, `app/src/androidTest/**`;
- distinct QA blobs: 820; corpus bytes: 7,320,692;
- corpus digest: `dfa3c80ad0c8a62a2c2c9a7f8a6fd1d29477b239fef09b9f2c3b8a224de53258`;
- zero-hit keywords across the whole corpus included `AbortableSlot`, `abort slot`,
  `bounded slot`, `EXT-X-KEY`, `playlist tag admission`, `media sequence`,
  `recording library row label`, `channel number assignment`, `DASH listed live`,
  `series plan`, `playback evidence`;
- named exclusions: QA-47 (recording-worker shutdown journal), QA-48 (bounded stream copy),
  QA-19 (source acquisition), QA-12 (crash/partial-write recovery), QA-44 (timeshift ring
  filesystem), QA-52 (player reuse), QA-59 (DASH listed-live refresh/grace), QA-58
  (resume-position persistence), QA-57 (recording-library label), QA-56 (display number),
  QA-55 (XMLTV genre tokens), QA-54 (favorite key), QA-53 (M3U hygiene), QA-38..QA-51
  and all earlier QA slices.

Adjacent but distinct: the base JVM unit test `core/src/test/.../AbortableSlotTest.kt` asserts
five simple single-thread behaviours. It has no byte-pinned fixture, RED mutation suite,
concurrency/lock-scope probe, corrupt-input table, privacy scan, heap/thread bounds or
instrumented mirror. No QA gate references this class, so this slice has no equivalent gate.

## Fixture contract

`qa/fixtures/qa60/` regenerates byte-identically from fixed constants. Files:
- `rows.tsv` — 24 rows (cap binds), 8 families x 3 seeds, five tab-separated columns;
- `dimensions.tsv` — pinned counts, cap 512 bytes per corrupt payload, mirror digest;
- `corrupt.txt` — seven named malformed blocks, each with a pinned first rejection code;
- `report-copy.txt` — bounded scratch diagnostics prose;
- `sha256.txt` — per-file digest manifest;
- `.gitattributes` — LF pinning on Windows.

Pinned digests:
- `rows.tsv` `a9747896677e6535089cb40cfeba769b2f87d50f37754b51a169e2b14bbd01b2`
- `corrupt.txt` `7d4cb545cf75004435b40486313d8427ada3bacbe11465407473c7dec6b63ebe`
- `dimensions.tsv` `1b7b7bc049b0948213b62131d7f827d11e2e81458a4e69b9a62a683371b0766d`
- `report-copy.txt` `5911e74ea5db082c8ffd686ac2973fd84949b2f96b2b99bbc59cea678811a864`
- `sha256.txt` `415e6f7a0b9c7af31b21a480a3e0cda8d5d352390f2a9cb7159546a3e0ca3316`

Identifiers are invented scratch tokens. No provider address, credential, PIN, secret, DRM key,
recording payload or private media is stored. The privacy marker is derived at run time from a
fixed seed and is never written as a literal.

## Checks and controls

Seventeen named checks are emitted as tab-delimited `CHECK` lines:
`HEADER_STRICT`, `ROW_BOUNDS`, `MANIFEST_CONSISTENT`, `BYTE_IDENTITY`, `PINNED_DIGEST`,
`CAP_BINDING`, `CORRUPT_FAIL_CLOSED`, `LATE_REGISTRATION`, `RESET_CLEARS_ABORT`,
`RELEASE_STALE`, `ABORT_IDEMPOTENT`, `RESET_FORGETS_CURRENT`,
`RACE_CALLBACK_OUTSIDE_LOCK`, `CONTRACT_VERDICTS`, `PRIVACY_SCAN`, `MEM_STABLE`,
`THREAD_STABLE`.

Twelve scratch-only RED controls each must exit nonzero with a pinned first code:
fixture byte drift, remanifested byte flip, digest drift, cap drift, verdict drift,
corrupt-input relaxation, privacy marker, late-registration escape, reset retaining abort,
stale release clearing the current value, repeated abort, and reset retaining the current value.
Production-copy mutants are compiled from scratch; the committed production file is never edited.
Hashes of owned sources, fixtures and the production input are compared before and after mutation
and must be identical before the unchanged GREEN run.

## Commands (offline, JDK 17)

```
bash scripts/qa60/run.sh generate   # write pinned fixture bytes
bash scripts/qa60/run.sh verify     # regen proof, 12 RED controls, RESTORE, GREEN (default)
bash scripts/qa60/run.sh gate-only  # GREEN without mutation controls
bash scripts/qa60/agp_compile.sh    # offline AGP compile/assembly attempt
```

GREEN runs under `-Xmx96m` with a bounded 2-thread latch probe and zero thread drift.
Instrumented execution requires an authorized adb target or emulator and is not claimed here.
