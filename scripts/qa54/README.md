# QA-54 — bounded favorite stable-key admission host gate

Deterministic, offline, zero-new-dependency host gate over the **real compiled** pure-Kotlin
`core/FavoriteChannels.kt` (`toggle`, `filter`) and `core/Models.kt` (`Channel.storageKey`).
No production, Gradle, workflow, shared document or sibling QA path is edited. No PR is
opened and no merge is performed by this worker. Base SHA:
`a523c2e43d198ee752f7c4f03f9f7853356ffb30`.

## Slice and audit boundary

Cross-branch audit scanned 288 local and origin worker refs and 11,529,267 bytes of prior QA
scripts, fixtures and androidTest text. Zero hits for `FavoriteChannels`, `favoritesOnly`,
`FavoriteChannels.toggle`, `storageKey in favorites`, `blankIdChangesNothing` and `qa54`.
Adjacent work is deliberately excluded: QA-02/06/15 (large EPG), QA-05 (name search),
QA-07 (PIN store), QA-09 (doctor reports), QA-11 (backup/restore), QA-17/49 (SAF storage),
QA-20 (parental decisions), QA-25 (DVR catalog paging), QA-27 (theme tokens),
QA-28 (multiview), QA-29 (final states), QA-37 (series rules), QA-41/47/48 (DVR),
QA-43 (source hygiene), QA-44 (timeshift ring), QA-45 (source restoration),
QA-46 (body length), QA-50 (EPG import), QA-52 (player reuse), QA-53 (M3U import).
The base `FavoriteChannelsTest` is a JVM unit test, not a fixture-driven host gate.
Finding: NO_EQUIVALENT_GATE.

## Commands

    bash scripts/qa54/run.sh generate    regenerate committed fixtures byte for byte
    bash scripts/qa54/run.sh verify      regen proof, 11 RED mutants, restore proof, GREEN
    bash scripts/qa54/run.sh gate-only   GREEN gate only
    bash scripts/qa54/agp_compile.sh     offline compile + instrumented APK assembly

`verify` copies production and fixtures into a private scratch build. All mutants are
applied there; the repository files are never edited. Each mutant must exit nonzero with a
distinct pinned FIRST code. The gate runs under `-Xmx96m`, with 32 channels, 48 cases (24
toggle + 24 filter), 8 corrupt rows and a 512-byte line cap. The mirror runs the same
framework-free checks from `app/src/androidTest` through an `android.app.Instrumentation`
runner; no device execution is claimed.

## Observed results (JDK 17.0.21.1+2, Kotlin 2.0.21 offline compiler jars)

- RED: 11/11 mutants exited 1 with pinned FIRST codes: BYTE_IDENTITY, MANIFEST_CONSISTENT,
  PINNED_DIGEST, HEADER_STRICT, ROW_BOUNDS, CAP_BINDING, CORRUPT_FAIL_CLOSED, PRIVACY_SCAN,
  TOGGLE_CASES (two source mutants), FILTER_CASES. Three mutants touch scratch production
  copies; eight touch scratch fixture copies.
- RESTORE: owned sources + fixtures SHA-256 aggregate identical before and after every
  mutation run; `git diff` of both production files empty. The runner prints the exact
  aggregate hash on each run.
- GREEN: 15/15 checks PASS, FIRST NONE, heapMax=100663296 bytes (96 MiB), threads 7 before
  and 7 after (drift 0), privacy patterns 0, 8/8 corrupt rows strictly rejected and accepted
  by the deliberately lax reference parser.
- Fixture mirror digest: `a02b0e0506c504f7e95b580ccd0622662b1ada82eabab71da28ced6b1b9416ae`.
- AGP: offline `:app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest` PASS;
  test APK 35,754 bytes, SHA-256
  `6f7b7c9fa35309be15644cc9e4f3baa48b4b328976b22e5886c569c365b56c41`; instrumentation entry
  `com.devtunasoft.tuneros.qa54.Qa54Instrumentation` present in the assembled manifest.
- Device execution: BLOCKED / NOT RUN — no authorized adb target is declared for this task.
