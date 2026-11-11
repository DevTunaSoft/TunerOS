# TunerOS roadmap

The master research backlog contains 134 prioritized items. Short-term release sequence:

1. **Foundation** — native application, source parsing, EPG, channel selection, basic playback, CI, verified APK.
2. **Guide** — full classic cable grid, channel search, user-controlled hide without EPG, favorites, stable channel mappings, Guide Doctor.
3. **Playback** — hardened switching, HLS/DASH/non-HLS validation, Stream Doctor, recovery, diagnostics.
4. **DVR/PVR (in progress)** — bounded direct-to-disk raw and MPEG-TS HLS capture, Record Now, exact-alarm future scheduling, recording library/playback, recovery and conflict handling. Protect/delete controls and user-selected local SAF recording destinations are now implemented in source. Next: real-device USB/SD acceptance, series rules, timeshift, encrypted/fMP4 compatibility where safe, and real-device acceptance.
5. **Multiview** — two concurrent streams and audio selection first, then adaptive three/four tiles; never exceed decoder or provider concurrent-stream limits.
6. **Personalization** — guide themes, profiles, backups, parental controls, accessible TV focus.
7. **Later** — advanced source adapters and optional authorized channel catalog.

**Deferred:** FastChannels serving from the Android box. Preserve this feature as an architectural proposal, not a current acceptance target.

## Decision log

- 2026-11-08: Rename Android app to TunerOS.
- 2026-11-08: Keep original browser app separate. Treat old V8/V9 source as unidentified; do not overwrite an unrelated repository.
- 2026-11-08: Native Android player is Kotlin + Media3, shared testable JVM core, TV/mobile shell.
- 2026-11-08: Move two-to-four channel selectable-audio Multiview into early Android roadmap, after basic player stability.
- 2026-11-08: Defer the FastChannels on-device server until the core app is working.

## Stabilization checkpoint — 2026-11-10

Address source audit findings before adding new UI features. Changes include virtualized search, binary-search guide index, timezone/truncation warnings, secure source URL storage, redirect and gzip handling, and targeted regression tests. Validate CI and on-device behavior. Add a standard Gradle wrapper when the binary can be committed safely. Real Multiview audio selection remains future work, not a tested feature.


Windows bootstrap helper added as a temporary alternative to manually installing Gradle. A **standard Gradle wrapper** is still open; verify the helper on a Windows 11 machine and commit the official wrapper JAR/scripts after source verification.


## DVR implementation checkpoint — 2026-11-10

DVR is now active development rather than a later placeholder. The recorder is designed so recording duration does not grow its working buffer: raw streams use a fixed 64 KiB copy buffer, and HLS reuses one 64 KiB segment buffer while polling bounded manifests and retaining only new segment metadata. Future jobs are persisted separately from recording history; credential-bearing source URLs and provider headers are kept in Android Keystore-backed encrypted storage rather than plaintext SQLite. Exact-alarm permission is required before a background future recording is armed on modern Android. Encrypted HLS, fMP4 HLS, separate rendition muxing and DASH recording remain explicitly unsupported until implemented and tested.


## 2026-11-11 integration and validation priority

1. **COMPLETE:** Reconciled and merged PR #1 (classic cable + experimental DVR) to `main` at `d618c91`; later fixes and regression tests now pass on `53d5b7a`.
2. Run Android TV/mobile manual acceptance: remote navigation and horizontal DVR actions, rotation restoration, stream switching, playback, saved sources.
3. Run real DVR acceptance: HLS/raw capture, interrupted/partial files, exact-alarm reboot recovery with >200 jobs, SAF USB/SD removable storage, storage pressure and provider failure.
4. Benchmark 1 GiB/2 GiB guide imports and long recording sessions before asserting measured memory savings. Keep XMLTV windowing off until future-only guide semantics and refresh policy are implemented.

**2026-11-11 main integration:** PR #1 merged at `d618c91`. Main CI run 42972145588 passed tests and debug APK assembly. The next pure-core hardening slice validates HLS byte-range byte counts (CI pending). Prioritize real-device acceptance and bounded Stream Doctor diagnostics before expanding DVR formats or multiview.


## Independent next workstreams (reconciled against main 53d5b7a)

| Priority | Workstream / file scope | Acceptance | State |
|---|---|---|---|
| P0 | Android device QA, no code edits: Android TV navigation, focus, rotation, URL encryption/migration and live Media3 playback | Timestamped device logs, reproducible PASS/FAIL cases | BLOCKED: no target device/emulator accessible to this execution |
| P0 | DVR hardware QA, no code edits: scheduled reboot, >200-job rearming, HLS/HTTP and SAF SD/USB fault injection | Verified recordings and recovery with no existing media loss | BLOCKED: no target hardware accessible |
| P1 | Core performance: `core/` XMLTV bounded indexes and data retention | Code/regression tests PASS at `53d5b7a`; measure heap on 1 GiB/2 GiB Android TV, preserve future-only EPG channels | CODE PASS; DEVICE MEMORY UNVERIFIED |
| P1 | Stream Doctor: Android Media3 error classification and safe user diagnostics (separate new helper + tests, no DVR storage edits) | Actionable messages without URL/header credential leaks; regression coverage | PASS (CI) at `2e9f612`; device UNVERIFIED |
| P2 | Playback/multiview: Android player controller, decoder constraints and selectable audio | Real HLS/DASH/non-HLS switching, decoder/release stress, memory caps | BLOCKED pending device playback acceptance |

The GitHub Actions suite passed at [run 42972740969](https://github.com/DevTunaSoft/TunerOS/actions/runs/42972740969) for source commit `26953c0`. Do not report this as production/device acceptance.


**2026-11-11 Stream Doctor source update:** Added a shared, no-background-work Media3 failure classifier in `core/` and wired it into both live and saved-video playback. This remains an initial failure-diagnosis slice, not automatic provider repair. [GitHub Actions run 42973394085](https://github.com/DevTunaSoft/TunerOS/actions/runs/42973394085) PASS at `2e9f612` (unit tests, APK, artifact). Preserve numeric codes and avoid showing stream URLs or exception messages to protect credentials. A subsequent task may add bounded user-consented diagnostics without private URL leakage.

**2026-11-11 playback hardening:** The Media3 player is now reused for live-channel switches with identical provider headers; different headers trigger a clean release/recreate. No device measurements yet. Stream Doctor code mappings are being aligned with documented Media3 constants, with specific tests for sign-in expiration, premium access, parental controls, invalid content types, out-of-range reads, and audio output failures. Device acceptance remains open.

**2026-11-11 DVR scheduler hardening:** Reuse a single schedule database helper across re-arm batches and isolate per-job alarm failures. Device reboot/exact-alarm stress tests remain a P0 acceptance gate. No increased background polling or periodic workers were added.

**2026-11-11 bounded raw stream progress:** Prevent CPU spin from input streams that repeatedly return zero bytes; abort after 16 consecutive no-progress reads, with regression tests. CI pending. Device acceptance still required for recording and battery impact.

**2026-11-11 source integration gate:** [Main CI run 42976022983](https://github.com/DevTunaSoft/TunerOS/actions/runs/42976022983) PASS at `53d5b7a` for core tests, debug APK compilation and upload. This covers guide index/filter, full channel browsing, XMLTV text caps, playlist bounded maps, playback reuse, DVR schedule and zero-read fixes, and Stream Doctor. Next unblocked priority is targeted Android instrumentation/acceptance harness (if emulator becomes available); manual real-device remote/stream/reboot/SAF cases remain BLOCKED without hardware.
