# TunerOS roadmap

The master research backlog contains 134 prioritized items. Short-term release sequence:

**Autonomous QA requirement (2026-11-11):** End users will not be asked to perform manual acceptance scripts. The app must execute and log its own safe local checks and observe real-device behavior. Existing manual QA references below describe uncovered scenarios to automate, not user instructions. Add on-device instrumented D-pad/focus, source-download, playback, recording, reboot/rearm, SAF fault recovery, and bounded memory/battery scenarios. No unexercised test is marked PASS. Initial autonomous runner has been added to main, CI/device acceptance pending. See `docs/ON_DEVICE_QA.md`.

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
2. Implement autonomous Android TV/mobile acceptance in-app: self-directed remote-focus/guide checks, lifecycle and rotation assertions, observed stream switching/playback, and secure saved-source validation.
3. Implement autonomous DVR acceptance: synthetic/real HLS and raw capture probes, interruption/recovery evidence, exact-alarm reboot telemetry with >200 jobs, SAF USB/SD fault observations, storage pressure and provider-failure classification.
4. Benchmark 1 GiB/2 GiB guide imports and long recording sessions before asserting measured memory savings. Keep XMLTV windowing off until future-only guide semantics and refresh policy are implemented.

**2026-11-11 main integration:** PR #1 merged at `d618c91`. Main CI run 42972145588 passed tests and debug APK assembly. The next pure-core hardening slice validates HLS byte-range byte counts (CI pending). Prioritize real-device acceptance and bounded Stream Doctor diagnostics before expanding DVR formats or multiview.


## Independent next workstreams (reconciled against main 53d5b7a)

| Priority | Workstream / file scope | Acceptance | State |
|---|---|---|---|
| P0 | Android in-app QA instrumentation: TV navigation, focus, rotation, URL encryption/migration and live Media3 playback | Timestamped automatically collected device logs, reproducible PASS/FAIL cases; no manual tester | PARTIAL runner implemented, device/execution UNVERIFIED |
| P0 | DVR automated acceptance: scheduled reboot recovery observation, >200-job rearming, HLS/HTTP and SAF SD/USB fault telemetry | Evidence automatically captured in-app; no existing media loss | PARTIAL DVR outcome logging, hardware acceptance BLOCKED |
| P1 | Core performance: `core/` XMLTV bounded indexes and data retention | Code/regression tests PASS at `53d5b7a`; measure heap on 1 GiB/2 GiB Android TV, preserve future-only EPG channels | CODE PASS; DEVICE MEMORY UNVERIFIED |
| P1 | Stream Doctor: Android Media3 error classification and safe user diagnostics (separate new helper + tests, no DVR storage edits) | Actionable messages without URL/header credential leaks; regression coverage | PASS (CI) at `2e9f612`; device UNVERIFIED |
| P2 | Playback/multiview: Android player controller, decoder constraints and selectable audio | Real HLS/DASH/non-HLS switching, decoder/release stress, memory caps | BLOCKED pending device playback acceptance |

The GitHub Actions suite passed at [run 42972740969](https://github.com/DevTunaSoft/TunerOS/actions/runs/42972740969) for source commit `26953c0`. Do not report this as production/device acceptance.


**2026-11-11 Stream Doctor source update:** Added a shared, no-background-work Media3 failure classifier in `core/` and wired it into both live and saved-video playback. This remains an initial failure-diagnosis slice, not automatic provider repair. [GitHub Actions run 42973394085](https://github.com/DevTunaSoft/TunerOS/actions/runs/42973394085) PASS at `2e9f612` (unit tests, APK, artifact). Preserve numeric codes and avoid showing stream URLs or exception messages to protect credentials. A subsequent task may add bounded user-consented diagnostics without private URL leakage.

**2026-11-11 playback hardening:** The Media3 player is now reused for live-channel switches with identical provider headers; different headers trigger a clean release/recreate. No device measurements yet. Stream Doctor code mappings are being aligned with documented Media3 constants, with specific tests for sign-in expiration, premium access, parental controls, invalid content types, out-of-range reads, and audio output failures. Device acceptance remains open.

**2026-11-11 DVR scheduler hardening:** Reuse a single schedule database helper across re-arm batches and isolate per-job alarm failures. Device reboot/exact-alarm stress tests remain a P0 acceptance gate. No increased background polling or periodic workers were added.

**2026-11-11 bounded raw stream progress:** Prevent CPU spin from input streams that repeatedly return zero bytes; abort after 16 consecutive no-progress reads, with regression tests. CI pending. Device acceptance still required for recording and battery impact.

**2026-11-11 source integration gate:** [Main CI run 42976022983](https://github.com/DevTunaSoft/TunerOS/actions/runs/42976022983) PASS at `53d5b7a` for core tests, debug APK compilation and upload. This covers guide index/filter, full channel browsing, XMLTV text caps, playlist bounded maps, playback reuse, DVR schedule and zero-read fixes, and Stream Doctor. Next unblocked priority is targeted Android instrumentation/acceptance harness (if emulator becomes available); manual real-device remote/stream/reboot/SAF cases remain BLOCKED without hardware.

**2026-11-11 high-priority XMLTV compatibility fix:** Replace unportable vendor-specific SAX flags with mandatory DTD rejection via lexical handler and external entity denial, preserving streaming bounded memory. New JVM security regressions; real Android Expat acceptance pending. This fixes a likely guide-import failure instead of adding redundant features.

**2026-11-11 timeline allocation reduction:** Indexed programme lists are already sorted. Reuse them in the guide renderer without another filtered/sorted intermediate list. Keep generic unsorted caller semantics and add randomized equivalence tests. Device RAM measurement remains a release acceptance gate.

**2026-11-11 playlist allocation reduction:** Preserve exact provider-scoped SHA-256 channel IDs but replace per-byte `String.format` hexadecimal conversions with a fixed 24-character encoding buffer; avoid per-URL `setOf` and lowercased scheme strings. Add regression cases proving IDs are byte-identical and source-scoped. No format/ID migration; device RAM metrics pending.

## 2026-11-11 in-app automated QA continuation

Implemented in source: optional debug-only 160-channel/11,520-programme guide stress with bounded allocations and wall/CPU/PSS/heap sampling; actual Android control focusability smoke; integration of both into the emulator CI release gate. Production startup has no stress or focus interruption. Test reports are now atomically published and event file I/O cannot wait behind long self-tests. **Build and emulator runs for newest commits are UNVERIFIED.** Remaining high-priority acceptance: verify the complete CI job, then autonomous real D-pad/key traversal and activity recreation, first-frame/recording/device recovery on hardware, and measured 1 GiB/2 GiB sustained memory/battery budgets without manual test scripts.

## 2026-11-11 TV guide D-pad boundary correction

**Source implemented, device acceptance pending:** ProgramTimelineView now handles both left and right D-pad boundary transitions by shifting the guide window in that direction, rather than accidentally yielding focus on the left edge. TimelineFocusPolicy gained an allocation-free directional selection fallback, and both the JVM core tests (including deterministic randomized regressions) and in-app offline timeline probe cover it. Local isolated Kotlin harness: 12 concrete assertions and 100,000 deterministic randomized direction cases **PASS**. Full Gradle/Android CI and physical remote verification **UNVERIFIED**. Next priority remains obtaining and investigating exact-commit emulator build results, then automatic hardware remote/lifecycle acceptance.

## 2026-11-11 low-memory & remote-navigation fixes

- **Source implemented:** Empty-guide timeline rows remain focusable even when no programme is selectable, so Left/Right can continue shifting windows across missing schedules. OK remains non-clickable on gaps. Debug-only autonomous Android view probe now asserts both directions and no accidental playback; emulator CI requires the new check, but full Android runtime acceptance is UNVERIFIED.
- **Source implemented:** `GuideSnapshot.programsBetween` avoids allocating a second filtered list for ordinary non-overlapping indexed EPG windows, retaining filtering when overlapping expired entries are present. A deterministic oracle test was added to `CoreTest`. **PASS:** isolated local Kotlin/JVM compiler and 40,000 randomized comparisons against a straightforward reference filter. Latest full Gradle/Android CI remains UNVERIFIED.
- **Source implemented:** The Activity skips its redundant second startup self-test when DVR rearming visits zero pending schedules; the scheduler returns a Boolean indicating whether it visited jobs. No added service, decoder, or background polling. Android runtime acceptance pending.
