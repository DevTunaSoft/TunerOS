# QA-47 — bounded recording-worker terminal shutdown gate

Slice: `core/RecordingWorkerLifecycle.kt::shutdownAfterPendingWork`, the terminal
service path that queues persistence cleanup behind accepted recording work and
then closes the single-thread worker. QA-01..QA-46 contain no gate for this seam;
the base-tree JVM test covers only two lifecycle cases, without a byte-exact
fixture, negative controls, privacy scan or resource bounds.

`run.sh` compiles the real pure-Kotlin production unit with the cached offline K2
compiler, compiles a Java gate against it, and runs:

- deterministic regeneration of five fixture files with a pinned aggregate SHA-256;
- strict manifest, dimensions, task-table and corrupt-variant parsers;
- five negative controls with pinned FIRST codes (fixture byte flip, corrupt-input
  guard, privacy detector, thread bound, production shutdown-order inversion);
- byte-identical restore proof over all ten gate inputs;
- three bounded worker cycles under `-Xmx64m`, with heap and live-thread bounds;
- a diagnostics privacy scan for provider URLs, credential assignments, PINs,
  recording payload text and the runtime-derived marker token;
- the Android-free mirror compiled and run on the JVM with the same pinned digest.

The instrumented sources under `app/src/androidTest/` compile in AGP but device
execution is not claimed without an authorized adb target or emulator.

Commands:

    bash scripts/qa47/run.sh
    ./gradlew --no-daemon --offline :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest

No production, Gradle, workflow or shared document files are owned or modified.
