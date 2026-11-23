QA-13 - deterministic bounded timeshift / live-window gate
==========================================================

Owned paths only: scripts/qa13/**, qa/fixtures/qa13/**, app/src/androidTest/**.
No production source, core, Gradle, docs, CI, manifest outside androidTest or shared status file
is changed. No PR is opened and no merge is performed by this worker.

Audit boundary (read before building this gate)
----------------------------------------------
- QA-02/QA-06 own large EPG parsing and recycled guide rows.
- QA-07 owns favourites and the parental PIN store; QA-11 owns TunerBackup restore.
- QA-08 owns DVR series rule conflicts and half-open schedule overlaps, not playback seek bounds.
- QA-10 owns OnDeviceSelfTest event aggregation.
- QA-12 owns DVR crash / partial-write catalog recovery.
- QA-13's distinct slice is app-level timeshift state: a bounded ring of overlapping live windows,
  half-open boundary clipping, Back / Live / absolute seek bounds, empty, single and touching
  windows, fail-closed corrupt or truncated rows, byte-identical fixture regeneration and
  secret-free diagnostics. It does not repeat the recording or crash-recovery checks.

Commands (JDK 17; offline Kotlin 2.0.21 jars from the local Gradle cache)
------------------------------------------------------------------------
  bash scripts/qa13/run.sh generate    regenerate the pinned scratch fixtures byte-for-byte
  bash scripts/qa13/run.sh verify      RED controls, then GREEN host checks and the JVM mirror
  bash scripts/qa13/run.sh red-green   explicit alias for the full verify path
  bash scripts/qa13/agp_compile.sh     offline AGP compile/package attempt when an SDK is present

The host gate is one Java 17 source compiled with javac; it writes and reads only scratch
directories and the committed fixture directory. Every derived marker is computed at run time
from a fixed seed, never written as a literal. Failures print check ids, counts and digests only.

Fixture dimensions: 256 normal channels x 14 overlapping window rows (one four-slot live gap),
2 empty channels, 1 single-window channel, 1 touching-window channel; 3587 windows, 2600 seeks,
15 named corrupt variants, 7 retained windows per channel, 60 s retention, 10 s Back step.
Derived case counts: 1280 strict overlap pairs, 514 merged unions, 2154 accepted seeks,
446 rejected seeks, 347 low-edge clips, 87 high-edge clips, 85 gap clips.

Thirteen on-disk RED mutants each must fail with a pinned FIRST code before the Green run:
byte flip, manifest flip, bad header, bad fields, truncated row, out-of-range bound, leak,
cap loosening, overlap removal, seek bound, unknown seek mode, permissive corrupt case, mirror
digest. Ordering keeps structural comparisons from masking the named semantic check.

Instrumentation declares only QA-13's runner. Sibling workers own sibling instrumentation
entries; the integrator merges canonical manifests. Device execution requires an attached,
authorized device or emulator: without one it is BLOCKED/UNVERIFIED, never a PASS.
