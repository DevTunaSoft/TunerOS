# QA-01 — compiled TV-guide layout and remote-key acceptance

Owned paths: `app/src/androidTest/**`, `qa/fixtures/tv-guide-layout/**`,
`scripts/qa_tv_layout_check.sh`. No application, core, Gradle, workflow or shared-doc file
is changed.

## What is added

- `app/src/androidTest/AndroidManifest.xml` declares one custom platform `Instrumentation`
  runner. No AndroidX Test, JUnit, Espresso or UIAutomator dependency is introduced.
- `app/src/androidTest/java/com/devtunasoft/tuneros/TvGuideLayoutInstrumentation.kt`
  launches the real `MainActivity` twice with synthetic `127.0.0.1` URLs, waits for the app's
  own import report, reads the compiled view tree and injects keys through the instrumentation.
  It writes at most 4 KiB to `files/qa-tv-layout-report.txt` with one PASS/FAIL line per check.
- The canonical fixture templates are in
  `app/src/androidTest/assets/tv-guide-layout/`; `generate_fixture.py` renders the same
  templates on the runner and verifies determinism with SHA-256.
- `scripts/qa_tv_layout_check.sh` is the bounded runner: static source gate, fixture
  determinism, APK compile, one device, one `am instrument` call, report assertion.

## Objective checks

1. `guide-loaded-vs-unloaded` — playlist-only state has 3 rows, source URL rows visible;
   guide-loaded state has 3 populated rows, source rows hidden, guide list not shorter.
2. `guide-row-bounds-no-clipping` — every visible compiled row stays horizontally inside the
   guide list and screen; cell and timeline have positive size and stay inside the row;
   row height is 48–96 dp; at least one whole row is inside the list and screen. Rectangles use
   `getLocationOnScreen` + measured width/height, so a clipped row is detected rather than hidden
   by the view's visible-rect API.
3. `dpad-focus-transitions` — injected `KEYCODE_DPAD_DOWN`/`UP` move cell focus between rows;
   `KEYCODE_DPAD_RIGHT`/`LEFT` select the next/current programme on the timeline.
4. `ok-select-to-play` — injected `KEYCODE_DPAD_CENTER` on a focused channel cell and on a
   focused programme timeline each cause the app to request that channel's loopback stream URL;
   the loopback server records the exact path. This proves selection-to-play **activation**
   only. It does not claim an HTTP success, decoded frame, codec, DRM or stable playback.

The fixture is exactly 3 channels / 9 programmes, anchored to the current half hour so the
"Now" programme is in the guide window. It contains only `127.0.0.1` stream URLs and no
provider address, credential, logo, DRM key or media byte. The stream path is answered with
404 on purpose: a request counts, no media is decoded. The test process closes the listener,
bounds it to 64 requests and joins its single daemon thread.

## Why this is not duplicate coverage

Read before implementing: `scripts/emulator-self-test.sh` and `scripts/emulator-tv-checks.sh`,
`OnDeviceSelfTest.kt`, `DeviceFocusChecks.kt`, `GuideFocusChecks.kt`, `CableGuideAdapter.kt`
and `GuideRowList.kt`.

- The TV shell script measures layout with `uiautomator dump` and runs the app's own debug
  focus probes; it collects a screenshot and prints dp numbers but does not compile an
  instrumented test APK or assert bounds inside the app.
- `GuideFocusChecks` already covers RIGHT/LEFT/UP/DOWN through the app's debug launch extra.
  QA-01 adds compiled instrumentation, an OK/select-to-play request assertion, and strict
  no-clipping bounds with a loaded-vs-unloaded comparison. It does not modify or rerun that
  debug probe.
- QA-02 (`qa/fixtures/README.md` on the sibling branch) is a JVM large-EPG parser/resource
  harness. QA-03 is a JVM playback/switching soak. Neither builds an APK or touches views.

## Run

On a scratch emulator/device with Java 17, the Android SDK and one authorized device:

```bash
bash scripts/qa_tv_layout_check.sh
```

Exit codes: `0` all four device checks PASS; `1` a compiled check or build failed; `2`
`NOT RUN` (no device, no SDK, no Java, or another prerequisite missing). The script never
clears app data and never uses a real provider. Use a scratch device: saved sources from
earlier runs are deliberately not deleted by QA-01.

## Evidence status

Static source gate: RED before these files existed (1/9), GREEN after (9/9).
Compilation: first attempt RED with two Kotlin errors (`run` override absent from the SDK
stub, and a labelled return in an expression body), both fixed in owned code.

Local compiled and device evidence on this worktree before commit:

- `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest` — PASS, APK built.
- Test APK manifest: package `com.devtunasoft.tuneros.test`, instrumentation
  `com.devtunasoft.tuneros.TvGuideLayoutInstrumentation`, target `com.devtunasoft.tuneros`;
  both fixture templates packaged as assets.
- `bash scripts/qa_tv_layout_check.sh` on one Android 35 TV AVD (`tv_1080p`, 1920x1080,
  `sdk_gphone64_x86_64`, WHPX, 2 GiB): 4 PASS / 0 FAIL. The report recorded
  `playlist-only rows=3 source=visible list=59px`, `guide-loaded rows=3 source=hidden
  list=184px`, one full 64 dp row inside the 184 px list, DOWN/UP and RIGHT/LEFT focus
  transitions, and OK requests for both synthetic stream paths with status
  `Connecting: QA Layout One/Two`.
- Earlier on the same code path, two device failures were reproduced and fixed: the hidden
  `Instrumentation.run()` hook was not dispatched (no report), and the status TextView repeats
  a channel name (cell lookup had to be restricted to `GuideRowList` rows).

This is an emulator result, not physical TV or remote-hardware evidence. The OK check proves
that a playback request was issued; it does not claim an HTTP success, decoded frame, codec,
DRM or stable playback. A missing SDK/device is `NOT RUN`, never PASS. This document does not
claim a workflow or physical-device result.
