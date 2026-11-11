# Autonomous on-device QA — TunerOS

## Release rule

**No end user is required to manually exercise features to qualify TunerOS.** On-device checks run without a test operator and capture objective results inside the app. CI/unit tests remain a supplementary build gate; they are not a substitute for on-device evidence. Release status is **BLOCKED / UNVERIFIED** when a scenario cannot yet be reproduced or observed automatically. Never turn an unexecuted scenario into PASS.

## Alpha implementation (2026-11-11)

- The existing `MainActivity` single-threaded worker runs `OnDeviceSelfTest` once after launch, and again after successful user-supplied M3U/XMLTV imports. The UI does **not** block, start an extra thread, repeatedly poll, or play a stream without the user's request.
- The self-test verifies a synthetic M3U channel, a synthetic XMLTV programme and indexed lookup, synthetic channel search/hide-no-EPG, timeline cell layout, refusal of XMLTV DTD injection, a 1 KiB direct-to-disk raw recording copy to an owned scratch file, an isolated Android Keystore AES-GCM canary roundtrip, exact-alarm authorization, and selected external storage visibility/free space.
- Source-import presence and real provider playback observations use the actual application state. A player reaching `isPlaying` is evidence of starting playback, **not** proof of codec/DRM compatibility, first decoded video frame, or long-term stability.
- Missing playlist/guide, unsupplied optional external storage, and incomplete network/hardware scenarios are `NOT_RUN`, not failures or passes; missing exact-alarm authorization is `BLOCKED`.
- Fixed-format numeric observations (wall timestamp, monotonic time since boot, event name, count/error code, process PSS KiB) log successful/failed playlist and guide imports and real live playback requested/ready/started/first-frame/error/30-second timeout, import duration, and real DVR recording completion/partial/failure/cancellation/interruption recovery. No URLs, titles, HTTP headers, credentials, stack traces, exception messages or stream contents are logged.
- The app retains one small latest-run text report and a capped 32 KiB events file privately in app storage; old events are discarded at the cap. Nothing is uploaded. The in-app `Self-test report` action can display the result, explicitly share it, or re-run checks. New tests use the existing worker.
- Standalone local synthetic tests do not write to the user's recordings database or media library, schedule alarms, alter external storage, or force-stop/reboot the device.

**Verification state:** source added to `main` on 2026-11-11; Android build and on-device runtime evidence for the newly added runner **NOT YET CONFIRMED**. Keep this distinct from earlier PASS CI snapshots.

## Automated acceptance still to implement

| Coverage area | Required autonomous evidence | Current state |
|---|---|---|
| Guide UI / TV remote | In-app instrumented D-pad focus traversal, search/filter and rotation recreation, screenshot-independent assertions | NOT RUN |
| Real feed imports | Bounded timing, success/failure classification, stream-specific exception codes, peak/PSS snapshots on real imported feeds | PARTIAL: duration and PSS snapshots; reproducible stress benchmarks missing |
| Live video / audio | Observe actual Media3 first frame, audio readiness, errors, buffering, switching/timeouts, release on stop; don't start unrequested streams | PARTIAL: requested/ready/isPlaying/first-video-frame/error/30-second one-shot timeout |
| Recorder / scheduler | Owned synthetic fixtures, recovery without touching user media, alarm re-arm telemetry, exact alarm trigger, failure classification | PARTIAL: synthetic copy, permission probe, real DVR completion/partial/failure/cancellation and interrupted-row recovery; scheduler/reboot instrumentation pending |
| SAF USB / SD | Read-only grant/capacity probe, evidence from actual capture, disconnection/reconnection recovery when it occurs | PARTIAL: grant and reported free space only |
| Stress / memory / battery | On-device bounded scenario runner with process PSS/peak, CPU, time, battery and device-class budget gates (1/2 GiB), no runaway allocations | NOT RUN |
| Releases | Signed APK reproducibility, package upgrade/downgrade migration, crash recovery and complete status ledger | NOT RUN |

## Unsimulatable environmental faults

A normal third-party Android app **cannot silently power-cycle itself, physically disconnect a USB drive, manufacture an independent provider outage, grant restricted OS permissions, or guarantee access to all hardware decoder configurations**. Such tests must be instrumented to observe real events, use an authorized external test rig, or remain **NOT RUN/BLOCKED**. They are never assigned to the end user as manual steps.

## Engineering rules

1. All diagnostics must run locally, with no added telemetry service, wake lock, unnecessary background loop, or large in-memory event history.
2. Never perturb live playback or active/scheduled recordings. Use synthetic content and separately owned scratch storage for active tests.
3. A test may emit PASS only for assertions it actually executed, with wall/monotonic timestamps and device context.
4. Avoid credential-bearing exception messages and external URLs in test logs. Sharing remains explicitly user initiated.
5. Preserve incomplete evidence across restarts using bounded private storage and add regression tests for each observed defect.

## Android emulator release gate (added to workflow 2026-11-11)

The `android.yml` workflow builds the debug APK, then an independent Android 35 emulator job (2 GiB RAM, 256 MiB heap) installs/launches TunerOS, waits for its own unattended report, and asserts **PASS** for the synthetic playlist parser, XMLTV parser/DTD rejection, guide filtering and timeline, 1 KiB recording copy, isolated Android SQLite recording catalog (collision/protect/progress/interruption), and dedicated Android Keystore canary. The report is uploaded as a 14-day artifact. An emulator failure or absent check fails CI rather than silently being reported as success. Results for this new emulator job remain **UNVERIFIED** until GitHub Actions executes the exact commit.

Real playback and DVR events also record first video frame, playback errors and one-shot startup/rebuffer timeout events. DVR alarm re-arm batches report exact/blocked/failed counts and the actual alarm receiver records delivery. These observations cannot prove arbitrary provider compatibility or physical storage/power fault resilience. A scheduled alarm request is not equivalent to a delivered alarm. No test changes the actual recordings database; the SQLite fixture uses a separate disposable database.

## 2026-11-11 debug-only autonomous stress and focus integration

- Added `OnDeviceGuideStress`, executed **inside the app** only when it is a debuggable build started with `--ez tuneros.debug.qa true`. This test generates exactly 160 channels and 11,520 programme entries in memory, verifies indexed current-program and three-hour window queries, timeline continuity, and searchable/hide-no-EPG results. It records wall time, thread CPU time, process PSS snapshots and Java heap growth. Its current smoke gates are <=10 seconds and <=32 MiB sampled heap growth. These values are acceptance thresholds for a bounded synthetic fixture, **not** a measured 1 GiB/2 GiB production memory certification.
- The same debug-only launch performs a lightweight **actual Android view focusability** and next-focus probe on live controls. It does not claim complete D-pad navigation, remote key injection, TV hardware or touch acceptance. Production installations do not have focus moved by testing or run the synthetic stress suite.
- The emulator CI now requires the two additional `PASS` checks (`debug-large-guide-stress`, `debug-ui-focus`), in addition to prior eight smoke assertions, and rejects an in-progress report marker. All work runs inside TunerOS; CI merely boots the device and collects objective results.
- Diagnostic event file reads/writes have a separate short critical-section lock from the long-running self-test lock, so active DVR recorder threads do not wait behind a guide benchmark. Self-test reports are written to a pending file and atomically published when complete; interrupted runs retain their marker for detection.
- **Code committed, static integrity checks PASS; Android/Kotlin compilation and emulator execution remain UNVERIFIED for this exact commit.** Static checks verified all 34 diagnostic event references resolve to 31 declared event types, all 10 CI required checks exist, production stress remains gated, and Kotlin file brace counts match. These checks do not replace actual compilation or runtime evidence.

## 2026-11-11 clock refresh regression and resource gate

- Production code: `ProgramTimelineView.refreshNow` updates only a timestamp and invalidates the canvas when the former/current marker intersects the visible window; `CableGuideAdapter.refreshClock` visits only already-attached `ListView` children, without `notifyDataSetChanged` or programme row construction. `MainActivity` stops its minute callback in `onStop`, does not schedule one without a loaded guide or while browsing recordings, and restarts the callback on guide import or returning to the guide.
- Debug-only on-device probe: one 100×24 ARGB bitmap (~9.4 KiB of pixel storage) draws a synthetic timeline, asserts the marker at its initial position, asserts it moves on `refreshNow`, then asserts it disappears when the timestamp leaves the window. The bitmap is explicitly recycled. No provider, recorder, guide mutation or hardware decoder is involved.
- `debug-now-marker` is required by the Android emulator CI self-test gate. The three debug UI probe outcomes are now read with **one** bounded scan of the existing 32 KiB event file, replacing multiple scans. This CI gate is **SOURCE CONFIGURED, RUNTIME UNVERIFIED**. No actual device CPU/RAM improvement is claimed until instrumented measurements.

## 2026-11-11 XML nesting resource gate

The XMLTV parser now rejects element depth above 64, avoiding unbounded deep-nesting behavior on malformed feeds. The Android 35 debug-only autonomous suite now requires `debug-xmltv-depth-limit` to return PASS using a synthetic, well-formed document with 65 nested levels; no network access, provider stream or user interaction is required. The runner already tests XMLTV DTD denial separately. Local isolated JVM SAX checks PASS; actual emulator execution of this newest gate is **UNVERIFIED**. Existing production self-tests are unchanged: the extra depth test runs only with the explicit debug QA launch extra.
