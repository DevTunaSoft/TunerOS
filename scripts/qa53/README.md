QA-53: bounded M3U import metadata hygiene, source-scoped identity and bounded
header interning, driven by the real compiled core PlaylistParser.

Audit boundary (QA-01..QA-52 audited first across 425 refs / 33,421,115 QA corpus bytes):
- QA-02 checks large EPG fixture determinism and counts only; it does not inspect parsed
  channel metadata, stable identity or repeated small-catalog aggregation.
- QA-09/QA-10/QA-11 own shareable doctor reports, event-log aggregation and backup
  first-failure codes on other data shapes.
- QA-15/QA-16 own repeated EPG import retention and cancellable staging, not M3U
  EXTINF attribute admission or source-scoped identifiers.
- QA-19 owns source download acquisition, not playlist parsing.
- QA-28 owns multiview tile admission; CORE-07 owns JVM chooseAudioTile tests.
- QA-47..QA-52 own shutdown journal, storage copy, SAF admission, EPG import rows
  and player reuse respectively.
Six production symbols (stableId, storageKey, shareHeader, parseExtinf,
attributeValue, isAllowedUrl) returned zero hits in the whole cross-branch QA corpus.
Finding: NO_EQUIVALENT_GATE.

Commands (repository root; JDK 17 and cached Kotlin 2.0.21 compiler jars required):
  bash scripts/qa53/run.sh generate    regenerate committed scratch fixtures
  bash scripts/qa53/run.sh verify      regen proof, 11 RED mutants, restore proof, GREEN
  bash scripts/qa53/run.sh gate-only   GREEN gate without mutation controls
  bash scripts/qa53/agp_compile.sh     offline compile + instrumented APK assembly

The gate compiles the read-only core sources PlaylistParser.kt, BoundedLines.kt
and Models.kt together with the contract and host main. All mutations of production
are applied only to scratch copies. No production file is edited by this task.
Device execution requires an attached authorized adb target and is BLOCKED/NOT RUN
in this worker; the instrumented mirror is source-compiled only.
