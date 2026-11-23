# MV-04 — multiview lifecycle churn acceptance source

Deterministic, zero-dependency QA source for bounded multiview tile create/release churn. It is a
source-level acceptance harness: the JVM gate proves the trace contract and its negative controls,
and the Android instrumentation replays the same trace on-device with real `HandlerThread`s and
synthetic player tokens. It does not modify application code, Gradle files, CI or shared docs.

## Contract (both implementations agree)

Fields per trace line, tab separated:
`seq, op, arg, result, active, audible, players, threads, refusals`.

- `CAPACITY = 2`, at most `MAX_STEPS = 64` steps and `MAX_CREATED = 24` created tiles.
- `ACQUIRE id` below capacity creates one tile, one synthetic player token and one named
  `mv04-tile-<id>` handler thread. The first tile becomes the audio owner; later tiles start muted.
- `ACQUIRE id` at capacity is refused: no player, thread or tile is created. Fallback focuses an
  existing tile, so a populated trace always has exactly one audio owner.
- `RELEASE id` releases the player, quits and joins the handler thread, and transfers audio to the
  lowest remaining tile (or none when empty).
- `FOCUS id` switches the single audio owner to an existing tile only.
- `TEARDOWN` releases everything: zero players, zero handler threads, no audio owner.

Physical speaker state, media decoding and first frames are NOT claimed. "Audible" means the single
audio owner in this harness. MV-03 covers the compiled two-tile UI; this suite covers lifecycle
churn and resource release.

## Files

- `Mv04Scenario.java` — fixed-seed generator, independent verifier and three negative mutations.
- `generated/churn.trace` — committed canonical trace (49 steps, SHA-256 in gate output).
- `../../../app/src/androidTest/assets/mv04/churn.trace` — byte-identical loopback fixture asset.
- `../../../app/src/androidTest/java/com/devtunasoft/tuneros/Mv04LifecycleChurnInstrumentation.kt` —
  platform `Instrumentation` runner; no JUnit/AndroidX dependency.
- `../../../app/src/androidTest/AndroidManifest.xml` — runner registration.
- `../../../scripts/mv04_lifecycle_churn.sh` — bounded host gate and device runner.

## Host gate

Requires JDK 17+ and, for the compiled-source check, Gradle 8.9 with Android SDK platform 35:

```
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ./scripts/mv04_lifecycle_churn.sh
```

The JVM gate compiles with `javac`, regenerates the trace, compares it with the committed fixture,
verifies the positive trace and rejects three mutations:

- two audible owners → must fail `AUDIBLE_COUNT`;
- a player surviving teardown → must fail `LEAKED_PLAYER`;
- a third tile surviving a refused acquire → must fail `CAPACITY_EXCEEDED`.

A mutation that verifies as PASS is a gate FAIL. Source compilation uses the unmodified Gradle
build; no test dependency or build-file change is introduced.

## Device status

`adb devices` on the QA host reported no device and the scratch SDK has no emulator/system image.
Device execution is therefore **BLOCKED / UNVERIFIED**. Compilation and packaging of the test APK
is source-level PASS evidence only. To run on a scratch emulator/device, the script installs the
test APK, invokes `am instrument` with a shell timeout and asserts the seven exact report lines.

## Privacy

All fixtures are synthetic tile numbers and state fields. The only URL is the harness's own
`http://127.0.0.1:<ephemeral>/churn.trace`. No provider URL, credential, DRM key, media payload,
recording or real channel name exists in code, fixtures, logs or commits.
