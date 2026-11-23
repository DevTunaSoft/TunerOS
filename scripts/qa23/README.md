# QA-23 — bounded DASH SegmentBase `sidx` index admission gate

## Slice and audit

QA-23 covers **bounded admission of DASH SegmentBase `sidx` index boxes** by the real
production parser `DashSegmentIndex.parse`. It exercises header/box-type/version checks,
reference-table bounds, eight distinct fail-closed refusal reasons, a 10,000-reference
bounded parse under a 96 MiB heap, and a byte-identical scratch fixture.

Before choosing the slice, the sibling branches rooted at the same `main` were audited:

| Gate | Slice already covered |
|---|---|
| QA-01 | compiled TV-guide layout, D-pad/OK |
| QA-02 | large EPG fixture and bounded core-parser import |
| QA-03 | playback soak instrumentation result |
| QA-04 | scheduled-recording durability / rearm |
| QA-05 | guide search |
| QA-06 | large Classic Cable EPG |
| QA-07 | LF pinning, favorites, stores |
| QA-08 | DVR overlap and series-rule expansion |
| QA-09 | offline AGP assembly and diagnostics |
| QA-10 | bounded event-log aggregation |
| QA-11 | guide backup/restore |
| QA-12 | DVR crash recovery |
| QA-13 | timeshift live window |
| QA-14 | reminder scheduling |
| QA-15 | repeated EPG import retention |
| QA-16 | cancellable guide-import staging |
| QA-17 | SAF destination / recording location lifetime |

Explicitly not duplicated: QA-18 evidence envelope, QA-19 source acquisition,
QA-20 parental decisions, QA-21 report publication, QA-22 HLS AES-128 IV admission.
No `scripts/qa23/**` or `qa/fixtures/qa23/**` path existed on any branch before this work.

No QA gate covers DASH `sidx`; the pure-Kotlin production unit was compiled into this
gate rather than reimplemented, so the checks run against shipped code.

## Owned paths

- `scripts/qa23/**` — gate, mutators, privacy scan, shell runners
- `qa/fixtures/qa23/**` — pinned LF fixture (contract, cases, 120,032-byte index, manifest)
- `app/src/androidTest/**` — JVM/device mirror, instrumentation, canonical manifest

No `core/src/**`, `app/src/main/**` or shared doc was edited. The production files are
compiled read-only: `DashSegmentIndex.kt`, `HlsPlaylistParserCore.kt`, `BoundedLines.kt`.
Their digests are hashed before and after every mutant probe.

## Fixture

Fixed constants generate all fixture bytes; `run.sh generate` rewrites them, `verify`
regenerates and compares byte-for-byte. `sha256.txt` pins `contract.txt`, `cases.tsv` and
`large.idx`; `dimensions.tsv` pins the manifest digest and the 10,000-reference model
digest. `.gitattributes` pins `* -text` so a checkout cannot introduce CR bytes.

`cases.tsv` rows: `id, hex, rangeOffset, rangeLength, expect, detail`. Thirteen cases
cover two valid boxes (v0, v1) and eleven refusal paths.

## Checks (GREEN: 21/21)

`BYTE_IDENTITY`, `MANIFEST_DATA`, `DIMENSIONS`, `CASE_COVERAGE`, `EXPECT_CONSISTENCY`,
`TRUNCATED_REJECT`, `TYPE_REJECT`, `VERSION_REJECT`, `TIMESCALE_REJECT`,
`NO_REFS_REJECT`, `HIER_REJECT`, `EMPTY_REF_REJECT`, `ZERO_DUR_REJECT`, `RANGE_REJECT`,
`OVERSIZE_REJECT`, `OVERFLOW_REJECT`, `SIDX_VECTOR` (independent byte decoder vs the
production parser), `CAP_BINDING`, `BOUNDED_CYCLES` (four parses, heap drift <= 8 MiB,
thread drift <= 2, no `qa23-` threads), `NO_STATIC_CACHE`, `PRIVACY_SCAN`.

## Negative controls (RED: 9/9, pinned first codes)

| Probe | Mutation | Pinned FIRST |
|---|---|---|
| byte-drift | one nibble inside a valid reference | `BYTE_IDENTITY` |
| sha-drift | same flip plus remanifest | `MANIFEST_MISMATCH` |
| expect-drift | `REJECT` -> `ACCEPT` on a corrupt row | `EXPECT_CONSISTENCY` |
| cap-shrink | only the dimension the cap check reads | `CAP_BINDING` |
| version-guard | accept version 2 | `VERSION_REJECT` |
| hier-guard | stop rejecting hierarchical refs | `HIER_REJECT` |
| empty-guard | stop rejecting zero-length refs | `EMPTY_REF_REJECT` |
| dur-guard | stop rejecting zero-duration refs | `ZERO_DUR_REJECT` |
| leak-inject | append derived token to diagnostics | `PRIVACY_SCAN` |

Each probe exits nonzero with its own exact code. Scratch sources are restored and
`cmp`-checked byte-for-byte; repository production and fixture trees are hash-checked
before and after the whole run.

## Privacy

`privacy_scan.py` and the gate's own detector scan emitted diagnostics and every fixture
byte for scheme URLs, credential/PIN/secret/payload patterns, and a seed-derived token
and marker that are never written as literals. Verdict: `PRIVACY PASS`.

## Commands

```
bash scripts/qa23/run.sh generate   # regenerate pinned fixtures
bash scripts/qa23/run.sh verify     # RED controls + restore proof + GREEN + privacy
bash scripts/qa23/device_gate.sh    # JVM mirror (96 MiB) + device availability probe
bash scripts/qa23/agp_compile.sh    # offline AGP compile/package + device probe
```

## Evidence

- Host gate: `COMPILE PASS` (Kotlin 2.0.21 / JVM 17, 4 sources, 768 MiB),
  `GREEN PASS checks=21 pass=21 fail=0`, `RED PASS controls=9`, `PRIVACY PASS`,
  `RESTORE PASS copies=4`.
- JVM mirror: `MIRROR PASS checks=8 pass=8 fail=0 heap=96m`.
- Offline AGP: `PASS qa23-agp` for `compileDebugAndroidTestKotlin + assembleDebugAndroidTest`,
  `APK bytes=16166`, `APK PASS instrumentation-entry-present`.
- Device execution: **BLOCKED / UNVERIFIED** — no authorized adb device or emulator.
  No device result is claimed.
- Integrator note: `app/src/androidTest/AndroidManifest.xml` contains only QA-23's
  instrumentation entry. It is a shared canonical path; merge QA-01..QA-23 entries when
  integrating. No PR was opened and no merge was performed by this worker.
