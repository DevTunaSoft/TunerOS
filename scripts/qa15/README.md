QA-15 - deterministic repeated large-EPG import retention gate
=============================================================

Owned paths only: scripts/qa15/**, qa/fixtures/qa15/**, app/src/androidTest/**.
No product, core, Gradle, workflow, shared doc or production manifest file is changed.
No PR is opened and no merge is performed by this worker.

Audit boundary
--------------
- QA-06 verifies a one-shot large EPG fixture's record/page/row structure and contiguity.
- QA-11 verifies TunerBackup guide-state encode/decode and restore.
- QA-13 verifies bounded timeshift live windows and seek bounds.
- PERF-02 verifies core multiview/recorder lifecycle resource bounds.
- Core tests cover GuideSnapshot index correctness against a linear reference.

QA-15's uncovered slice is the *repeated* import lifecycle: parse a large scratch XMLTV
fixture six times under a 96 MiB heap, discard each snapshot, then assert bounded
retention (no heap drift), stable thread count, exact channel/programme counts, sorted
window rows, contiguous rows that cover the whole window, row cell bounds, zero-width
window rejection, fail-closed corrupt/truncated input and secret-free diagnostics.
It compiles and calls the real pure-Kotlin core parser/index/layout sources offline;
the harness itself is the only thing mutated in the RED controls.

Fixture contract
----------------
- Deterministic seed 20261123; 4,096 channels x 24 programmes = 98,304 programmes.
- 13,685,995 bytes, SHA-256 d1ecac3dbf7ecf4bcfcaff96f6f3975e28ea8602fdb1c0478125f9586e5e284e.
- Only local scratch tokens ("scratch-local:qa15/...", "Scratch Channel ...", "Scratch Show ...");
  no URL, provider address, credential, PIN, DRM key, recording payload or real guide data.
- Byte-identical regeneration is checked against qa/fixtures/qa15/manifest.txt, along with
  a compact sidecar digest shared with the Android instrumentation mirror.
- Six fail-closed mutations: truncated, unbalanced, bad DOCTYPE, internal DTD subset,
  unsupported encoding and duplicate DOCTYPE. Each must be rejected by GuideParser.

Commands (JDK 17; offline Kotlin 2.0.21 compiler jars from the local Gradle cache)
---------------------------------------------------------------------------------
  export JAVA_HOME=C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2
  bash scripts/qa15/run.sh generate     regenerate fixture, manifest and mirror digest
  bash scripts/qa15/run.sh verify       five RED source mutants, then GREEN gate + JVM mirror
  bash scripts/qa15/run.sh red-green    alias for verify
  bash scripts/qa15/run.sh gate-only    compile and run the GREEN gate without mutants/mirror
  bash scripts/qa15/agp_compile.sh      offline AGP compile/package attempt

RED controls (each must fail with the pinned FIRST check on a scratch copy)
--------------------------------------------------------------------------
- row-bound:   MAX_ROW_CELLS use narrowed to 4  -> ROW_CELL_BOUND
- order:       comparator changed to title      -> ORDER_SORTED
- guard:       reject guard condition inverted  -> GUARD_PROBE
- privacy:     scratch title token made secret-like -> PRIVACY_FIXTURE
- dimension:   channel bound 4096 -> 4095       -> MANIFEST_MATCH

After the mutant loop the original sources are hashed again and must be byte-identical.
Diagnostics emitted by the gate contain only check ids, counts, digests and exception class
names. The report is scanned before printing; a forbidden token produces PRIVACY_REPORT
instead of the offending line.

Instrumentation
---------------
app/src/androidTest declares only QA-15's runner in its own manifest; sibling workers own
their entries and the integrator merges canonical manifests. Qa15Assertions.kt is
dependency-free and runs both on the JVM and inside the test APK.

Status vocabulary
-----------------
Host checks are reported only when the exact command output shows PASS. An offline test
APK compile is not device acceptance. Device execution requires an attached authorized
device or emulator; without one it is BLOCKED/UNVERIFIED, never a PASS.

Exact-head evidence (JDK 17, offline Kotlin 2.0.21, no device)
--------------------------------------------------------------
- `bash scripts/qa15/run.sh verify` EXIT=0: 5/5 RED controls pinned, source restore byte-identical
  (sha256 c8a695793d11d4dc58c50c348c4ba6c7959ea942f45cce0e8302533c4512ceaa), GREEN 67/67,
  JVM mirror 15/15. Repeated-import heap drift 490488 B against an 8388608 B limit; thread drift 0;
  no static guide-parser registry; all six corrupt variants rejected.
- `bash scripts/qa15/agp_compile.sh` EXIT=0: offline compileDebugAndroidTestKotlin +
  assembleDebugAndroidTest PASS, 25974-byte test APK, QA-15 instrumentation entry present.
- Device: BLOCKED (no authorized adb/emulator target probed); physical TV acceptance UNVERIFIED.
