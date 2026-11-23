QA-24 runner
============

Files
-----
Qa24Gate.java          zero-dependency host gate: generate / verify / marker
run.sh                 RED negative controls then unchanged GREEN, -Xmx96m
kotlin_mirror.sh       JVM compile and run of the Android-free Kotlin mirror
agp_compile.sh         offline AGP compile/assembly of the androidTest source set
remainifest.sh         rewrites one scratch manifest entry after a mutation

Commands
--------
  bash scripts/qa24/run.sh red
  bash scripts/qa24/run.sh green
  bash scripts/qa24/kotlin_mirror.sh
  bash scripts/qa24/agp_compile.sh

The runner prints tab-delimited status lines only. It never prints fixture rows,
derived marker text, or a provider-style address.

Pinned negative controls (each must exit nonzero with the exact first code)
--------------------------------------------------------------------------
  M1_BYTE_FLIP       FIXTURE_BYTE_IDENTICAL   flipped byte in one scratch row
  M2_SORT_ORDER      SORT_ORDER               changed one scratch sort key
  M3_CLIP_GUARD      CASE_C06                 weakened the clip comparison
  M4_OVERFLOW_GUARD  CASE_C07                 widened the digit-width guard
  M5_EMPTY_INPUT     EMPTY_REJECT             added a row to the empty fixture
  M6_PRIVACY_SCAN    PRIVACY_SCAN             added a derived marker to scratch
  M7_THREAD_BOUND    HEAP_THREADS_BOUNDED     zeroed the observed thread cap

Source mutants are compiled to separate scratch directories from byte copies;
the committed gate source is never rewritten. Fixture mutants are scratch copies
with their SHA-256 manifest entry regenerated so a digest mismatch cannot mask
the named semantic failure. Semantic mode skips byte identity, pinned manifest
digest and pinned dimensions checks; it does not weaken the GREEN mode checks.

Instrumented mirror
-------------------
app/src/androidTest/AndroidManifest.xml declares only the QA-24 runner. When
integrating sibling QA source sets, merge their instrumentation entries rather
than replacing this file. Qa24Contract.kt is Android-free and shares the host
generator, rules and pinned digests; Qa24Instrumentation.kt writes a bounded
PASS/FAIL report to targetContext files and returns a result bundle.

Device execution requires an authorized attached device or emulator. Without
one it is BLOCKED / UNVERIFIED; a compiled APK is not a device PASS.
