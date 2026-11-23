# QA-27 — bounded EPG colour/theme token gate

Owned paths only: `scripts/qa27/**`, `qa/fixtures/qa27/**`, `app/src/androidTest/**`.
No production, core, Gradle, workflow, shared document or `app/src/main` file is changed.
No PR is opened and no merge is performed by this worker.

## Slice and exclusions

The slice is the EPG timeline colour/theme token registry: seven named roles
(surface, outline, glyph, live marker, three cell states) with alpha semantics,
glyph contrast, cell-state separation, marker separation and outline visibility
over a bounded scratch fixture. The production seam is the constant set in
`app/src/main/java/com/devtunasoft/tuneros/ProgramTimelineView.kt`; that file is
read and hashed for evidence only, never edited.

Audited and **excluded** because a sibling branch already owns the behaviour:

- QA-01 compiled guide layout and D-pad/OK. It references `ProgramTimelineView`
  for view bounds and focus only; it asserts no token value or colour relation.
- QA-05 small guide search and hide-no-EPG; QA-06 large 25k-channel paged EPG.
- QA-25 bounded recording-catalog paging (`pages.tsv`, 500 cap). Together with
  QA-05 and QA-06 this covers the candidate slice "guide/search result
  pagination", so QA-27 does not duplicate it.
- QA-13 bounded timeshift ring, window clipping and seek bounds. This covers the
  candidate slice "timeshift seek-map continuity" in full.
- QA-26 bounded DASH MPD planning and template expansion.
- QA-10 event-log marker names (`UI_CLOCK_MARKER_*`), not token values.
- QA-07 favourites/PIN store, QA-12 crash/partial-write recovery and the
  remaining QA-02..QA-24 gates are unrelated to token validation.

A cross-branch grep of every `hermes/worker-b*` branch under `scripts/**`,
`qa/**` and `app/src/androidTest/**` found zero matches for theme, colour token,
palette, luminance or contrast validation. The slice is genuinely uncovered.

## Fixture

`qa/fixtures/qa27/` holds `tokens.tsv` (338 bytes, seven rows), `dimensions.tsv`,
`contract.txt`, `sha256.txt` and eight named fail-closed variants under
`corrupt/`. All bytes are regenerated from fixed constants in one Java source.
The pinned canonical SHA-256 is
`8434d9d72d6a9b60f3cbe7ce7977736b5be376d53e6410ed5cee44dbd3b829c0`.
`.gitattributes` pins LF bytes; every fixture path passes `git check-ignore`.
No feed address, access marker, personal code, protection material or media
payload is stored. The synthetic marker is derived from the pinned seed at run
time, never written as a literal.

## Commands

From the repository root with JDK 17 exported as a native `C:/...` `JAVA_HOME`:

```
bash scripts/qa27/run.sh generate     regenerate committed fixture bytes
bash scripts/qa27/run.sh regen        cross-process byte-identity digest check
bash scripts/qa27/run.sh verify       GREEN host gate, -Xmx96m
bash scripts/qa27/run.sh corrupt      eight fail-closed cases with pinned codes
bash scripts/qa27/run.sh red-green    ten RED mutants, restore proof, then GREEN
bash scripts/qa27/run.sh privacy      independent Python privacy scan
bash scripts/qa27/run.sh all          regen + verify + corrupt + red-green + privacy
bash scripts/qa27/mirror_check.sh     JVM mirror of the Android-free assertions
bash scripts/qa27/kotlin_mirror_check.sh  offline K2 compile + run of Kotlin mirror
bash scripts/qa27/agp_compile.sh      offline AGP compile/package attempt
bash scripts/qa27/device_gate.sh      truthful device status
```

## Negative controls

Each mutant must exit nonzero and emit its pinned `FIRST` code. Semantic mutants
are remanifested and run with byte-identity checks suppressed so the named guard,
not a digest mismatch, fires first. The byte-flip mutant runs the full mode.

| Mutant | Pinned first failure |
|---|---|
| byteflip | BYTE_IDENTITY |
| header | HEADER_STRICT |
| rowbounds | ROW_BOUNDS |
| argb | TOKEN_ARGB |
| roleset | ROLE_SET |
| alpha | ALPHA_SEMANTICS |
| contrast | CONTRAST_BOUND |
| truncated | TRUNCATED_INPUT |
| marker | MARKER_DISTINCT |
| leak | PRIVACY_SCAN |

Restore is proven by comparing the production source SHA-256 and an aggregate
fixture SHA-256 before and after all mutants, plus a standalone copy comparison.

## Evidence on this worktree (base a523c2e43d198ee752f7c4f03f9f7853356ffb30)

- Regeneration: byte-identical aggregate fixture digest
  `57b9ffd4c6a522833df83ed289ef59d54c8f8c03a803f6006e81861247884975`.
- GREEN host gate: 15/15 checks PASS under `-Xmx96m`; bounded thread drift.
- Fail-closed: 8/8 corrupt and truncated variants rejected with their pinned codes.
- RED: 10/10 mutants rejected with their pinned codes; production and fixture
  SHA-256 byte-identical after restore.
- Privacy: 0 hits over 14 files / 5278 bytes, 6 generic patterns plus the
  run-derived marker, covering committed fixture bytes and the captured report.
- JVM mirror: 7/7 PASS. Kotlin mirror compiled with offline K2 and ran 7/7 PASS.
- Offline AGP `:app:compileDebugAndroidTestKotlin` and
  `:app:assembleDebugAndroidTest`: PASS; test APK 11894 bytes; instrumentation
  entry present in the APK manifest.
- Production source SHA-256 (evidence only, unchanged):
  `01091c43c4693b79842acf0b2ccd1f2362cb80a8dc7fcf05b754a2aaa056cf36`.
- Instrumented device execution: BLOCKED / UNVERIFIED — no authorized adb device
  or emulator attached. Compilation is not device evidence.

## Manifest note

`app/src/androidTest/AndroidManifest.xml` declares only QA-27's runner. Sibling
QA branches declare their own runners in the same path; the integrator owns the
canonical manifest merge.
