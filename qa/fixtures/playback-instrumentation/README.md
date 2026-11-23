# QA-03 on-device playback instrumentation (complement to the JVM soak)

## What this adds

The repository already has a bounded JVM playback soak (`scripts/qa_playback_soak_check.sh`,
`scripts/QaPlaybackSoakCheck.java`) wired into the `build` job. This directory adds the
**on-device half**, which that harness cannot provide:

- `app/src/androidTest/java/com/devtunasoft/tuneros/qa/QaPlaybackSoak.java` — pure Java,
  seeded generator + switch/lifecycle/failure model. The same source is compiled by javac
  for the local JVM check and by AGP into the androidTest APK.
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa/PlaybackSoakInstrumentation.java` —
  a plain `android.app.Instrumentation` (no JUnit/androidx.test dependency). It runs the
  shared soak on the device, then drives the **real** `OnDeviceSelfTest.observe` event sink
  with bounded synthetic playback events and scans the resulting app-private event file.
- `app/src/androidTest/AndroidManifest.xml` — test-APK-only registration of that
  instrumentation so `am instrument` can resolve it. The application manifest is untouched.
- `scripts/qa_playback_check.sh` — local JVM acceptance runner for the shared harness.
  It is intentionally **not** a second CI gate; CI already has the JVM soak step.

## Bounds and data

- 24 channels, 96 switches, 64 KiB total synthetic fixture, 512 diagnostic events,
  32 KiB diagnostic cap, 16 KiB report cap. Files are generated into app cache / `build/`
  and deleted in `finally`; no checked-in fixture media.
- All stream addresses use the reserved `.invalid` TLD. Four synthetic canaries (fake
  URL, token, DRM key and recording payload) are planted in the fixture and scanned for
  in both the model's diagnostics and the device's event file. No real provider,
  credential, DRM key or recording is used.
- Failure taxonomy: `OK`, `NO_RENDERER` (4001), `SOURCE_ERROR` (2001),
  `DECODER_LIMIT` (4003). Lifecycle tokens: `PAUSE_RESUME`, `RELEASE_RECREATE`, `NONE`.
  First-frame confirmation means one `PLAYBACK_FIRST_FRAME` diagnostic per successful
  synthetic switch and none for failures.

## Minimal workflow extension

The existing `.github/workflows/android.yml` was extended only to run this
instrumentation: add `:app:assembleDebugAndroidTest` to the existing build task, upload
and download that test APK, and append three commands to the **existing** emulator
script (`adb install`, `am instrument -w`, `grep`) plus one artifact line. No new job,
workflow, emulator, dependency, Gradle or manifest change.

## Local commands and honest labels

```
export JAVA_HOME=<jdk17>
export PATH="$JAVA_HOME/bin:$PATH"   # MSYS style path on this host
bash scripts/qa_playback_check.sh
```

The local JVM runner is **PASS** at the commit containing these files: deterministic
fixture, player/lifecycle invariants, failure taxonomy, bounded diagnostics and a
corrupted-expectation negative control. Java syntax/type checking of the Android wrapper
was done against local framework stubs: **PASS (stub compile only)**.

`./gradlew :app:connectedAndroidTest` and `./gradlew :app:testDebugUnitTest` are
**BLOCKED** on this host: `SDK location not found. Define a valid SDK location with an
ANDROID_HOME environment variable or by setting the sdk.dir path in ... local.properties`.

Consequently, compilation of the test APK by AGP, `am instrument` execution on the
Android 35 emulator, the real diagnostic-sink probe, and any real decoded video frame are
**UNVERIFIED** locally. The instrumentation itself never starts a decoder or network
stream; it does not claim real first-frame, codec, DRM, physical-TV or sustained-memory
acceptance.
