# Autonomous on-device QA — TunerOS

## Release rule

**No end user is required to manually exercise features to qualify TunerOS.** On-device checks run without a test operator and capture objective results inside the app. CI/unit tests remain a supplementary build gate; they are not a substitute for on-device evidence. Release status is **BLOCKED / UNVERIFIED** when a scenario cannot yet be reproduced or observed automatically. Never turn an unexecuted scenario into PASS.

## Alpha implementation (2026-11-11)

- The existing `MainActivity` single-threaded worker runs `OnDeviceSelfTest` once after launch, and again after successful user-supplied M3U/XMLTV imports. The UI does **not** block, start an extra thread, repeatedly poll, or play a stream without the user's request.
- The self-test verifies a synthetic M3U channel, a synthetic XMLTV programme and indexed lookup, refusal of XMLTV DTD injection, a 1 KiB direct-to-disk raw recording copy to an owned scratch file, an isolated Android Keystore AES-GCM canary roundtrip, exact-alarm authorization, and selected external storage visibility/free space.
- Source-import presence and real provider playback observations use the actual application state. A player reaching `isPlaying` is evidence of starting playback, **not** proof of codec/DRM compatibility, first decoded video frame, or long-term stability.
- Missing playlist/guide, unsupplied optional external storage, and incomplete network/hardware scenarios are `NOT_RUN`, not failures or passes; missing exact-alarm authorization is `BLOCKED`.
- Fixed-format numeric observations (wall timestamp, monotonic time since boot, event name, count/error code, process PSS KiB) log successful/failed playlist and guide imports and real live playback requested/ready/started/error. No URLs, titles, HTTP headers, credentials, stack traces, exception messages or stream contents are logged.
- The app retains one small latest-run text report and a capped 32 KiB events file privately in app storage; old events are discarded at the cap. Nothing is uploaded. The in-app `Self-test report` action can display the result, explicitly share it, or re-run checks. New tests use the existing worker.
- Standalone local synthetic tests do not write to the user's recordings database or media library, schedule alarms, alter external storage, or force-stop/reboot the device.

**Verification state:** source added to `main` on 2026-11-11; Android build and on-device runtime evidence for the newly added runner **NOT YET CONFIRMED**. Keep this distinct from earlier PASS CI snapshots.

## Automated acceptance still to implement

| Coverage area | Required autonomous evidence | Current state |
|---|---|---|
| Guide UI / TV remote | In-app instrumented D-pad focus traversal, search/filter and rotation recreation, screenshot-independent assertions | NOT RUN |
| Real feed imports | Bounded timing, success/failure classification, stream-specific exception codes, peak/PSS snapshots on real imported feeds | PARTIAL: import events; timed/memory benchmarking missing |
| Live video / audio | Observe actual Media3 first frame, audio readiness, errors, buffering, switching/timeouts, release on stop; don't start unrequested streams | PARTIAL: requested/ready/isPlaying/error |
| Recorder / scheduler | Owned synthetic fixtures, recovery without touching user media, alarm re-arm telemetry, exact alarm trigger, failure classification | PARTIAL: offline direct-to-disk copy and permission probe |
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
