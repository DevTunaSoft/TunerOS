# QA-64 — bounded timeline width-unit host gate

Base SHA: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`. Branch
`hermes/worker-c48-qa64`. Owned paths only: `scripts/qa64/**`,
`qa/fixtures/qa64/**`, `app/src/androidTest/**`. No production, core, Gradle,
workflow, shared document or sibling QA path is changed. No PR is opened and no
merge is performed by this worker.

## Slice and cross-branch audit

Slice: the real compiled pure-Kotlin `core/TimelineLayout.kt::widthUnits`
duration-to-width conversion: floor division, the one-unit minimum floor,
saturation at `Int.MAX_VALUE`, and rejection of nonpositive scale. The gate
drives the production function through 32 byte-exact fixture rows in eight
families, six corrupt variants and six repeated cycles under a 96 MiB heap.

Audit performed before authoring: 447 refs (288 distinct tips) were scanned
over `scripts/qa*`, `qa/fixtures/qa*` and `app/src/androidTest/**`
(1,003 path entries, 875 distinct blobs, 7,588,230 corpus bytes) and over all
JVM test trees on the same refs (12,478 path entries, 77,741,153 bytes).
Zero-hit keywords in the QA corpus: `widthUnits`, `unitsPerHour`,
`timeline-width`, `qa64`, `min-unit-floor`, `duration-to-width`. The JVM corpus
contains only two `widthUnits` assertions in `CoreTest.kt`, repeated across
branches; no QA gate calls the function.

Finding: NO_EQUIVALENT_GATE. QA-61 compiles `TimelineLayout` for
`TimelineFocusPolicy.shiftedIndex` but never calls `widthUnits`. QA-15 calls
`rowFromSorted` for retention; QA-06/QA-42 model guide rows and soak, not width
units; QA-27 covers theme tokens; QA-50 covers EPG import admission; QA-59
covers DASH live refresh. Exclusions: `docs/**`, root files, Gradle, workflows,
`app/src/main/**`, `core/**` (audit-only), sibling `qa*` trees, binary fixture
bytes counted but scanned as text, and every path outside this worker's
ownership.

## What is checked

Sixteen checks: strict headers and row bounds, byte identity, manifest
consistency, pinned digest, the production width verdict on every admitted
row, floor/saturation/scale families, cap binding, fail-closed corrupt input,
absence of static mutable state, bounded heap and threads with no QA-named
thread, privacy and diagnostic scans, and a pinned golden journal. The marker
is derived at run time and never written to a fixture, report or heartbeat.
Seven scratch mutants must each exit nonzero with their pinned `FIRST` code;
all scratch production and fixture copies are then restored and hashed
byte-identical. GREEN runs under `-Xmx96m`.

## Commands

```
bash -n scripts/qa64/run.sh
python3 -m py_compile scripts/qa64/mutate.py
bash scripts/qa64/run.sh generate   # regenerate qa/fixtures/qa64 byte-for-byte
bash scripts/qa64/run.sh gate-only  # GREEN without the mutation sweep
bash scripts/qa64/run.sh verify     # RED -> RESTORE -> GREEN
bash scripts/qa64/agp_compile.sh    # offline AGP compile/assembly + device probe
```

## Observed host result (JDK 17.0.21.1+2, Kotlin 2.0.21, -Xmx96m)

```
COMPILE   PASS  real TimelineLayout + Models
RED       PASS  7/7 mutants, each nonzero with its pinned FIRST code
RESTORE   PASS  10 files per case, owned tree and production digests unchanged
GREEN     PASS  checks=16 fail=0 first=NONE threads=7/7 qa-threads=0
DIAGNOSTIC PASS offenders=0, no derived marker in report or fixture
MIRROR    PASS  8/8 framework-free checks on the JVM
AGP       PASS  compileDebugAndroidTestKotlin + assembleDebugAndroidTest
APK       PASS  28499 bytes, instrumentation entry present
DEVICE    BLOCKED no authorized adb target
```

## Status vocabulary

PASS / FAIL / BLOCKED / NOT RUN / UNVERIFIED only. Host verification is not
device evidence. The instrumented mirror is compiled only; execution needs an
authorized adb target. Sibling branches each add their own root
`app/src/androidTest/AndroidManifest.xml` instrumentation entry; this branch
adds only `com.devtunasoft.tuneros.qa64.Qa64Instrumentation`. Merging those
entries is an integrator decision and is not attempted here.
