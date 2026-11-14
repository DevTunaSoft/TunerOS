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

## 2026-11-11 cold-start recovery and safe source updates

**Committed to main (Android build UNVERIFIED):** On activity creation, recover a small guide window/search state from the Android Bundle and reuse the existing serialized import worker to reload encrypted, last-validated source URLs after a cold process restart. Retained configuration-change state avoids unnecessary redownload. Reject feeds with no channels or programmes before replacing in-memory data or saved source. Reject saving superseded import results. A separate, allocation-free `SourceRecoveryPolicy` in pure Kotlin governs restoration and stale-result acceptance. Core regression tests and a synthetic in-app self-test were added; Android emulator CI asserts the new on-device policy check. Local isolated `kotlinc-jvm 1.9.0` policy execution: **PASS, 5,208 assertions**. No claim of full APK compilation or physical Android process-death acceptance yet.

**Next:** verify full `main` Gradle build and emulator QA; add one-shot autonomous real activity recreation/process-death evidence without duplicating source downloads, memory-heavy snapshots or background polling. Handle interrupted network reimport and preserve playback on configuration changes only after an explicit acceptance test.

## 2026-11-11 low-memory recovery guard follow-up

The Android search field now enforces a 256-character maximum, and saved activity state stores a capped query string plus a single guide timestamp—never an entire EPG, playlist, or credential-bearing URL. The recycler-based timeline discards any pending D-pad boundary shift when a ListView row is rebound to a different channel, and the existing debug-only in-app UI probe verifies that the new channel's preferred programme is not overwritten by stale focus intent. Changes are committed to main; full APK compilation and on-device lifecycle/UI verification remain UNVERIFIED. The local Kotlin source-recovery policy isolated test reports **PASS: 5,208 assertions**.

## 2026-11-11 bounded source I/O and M3U identity optimization

- **Source implemented:** `SourceDownloader` now fails closed after 16 consecutive non-progressing bulk reads. The cap applies to both ordinary parser reads and skip, preventing a malformed stream from monopolizing a CPU core. Temporary streams are closed when gzip setup or the two-byte content probe fails; no new background work, dependencies, or large buffers.
- **Source implemented:** `PlaylistParser` hashes source prefix, guide ID and URL incrementally instead of allocating a concatenated key per channel. The byte sequence and existing 96-bit hexadecimal channel IDs are preserved; no existing playlists, guide mapping or recording identifiers require migration.
- **PASS — isolated JVM evidence only:** local `kotlinc-jvm 1.9.0` compiled exact copied implementations and executed seven downloader scenarios, including stalled bulk read/skip, transient recovery, byte limit, and stream cleanup; a second local parser harness passed 301 original-SHA compatibility assertions. Added matching `CoreTest` regressions and an unattended Android `offline-source-stall` probe, now an emulator CI assertion. Latest Android Gradle build/emulator execution remains **UNVERIFIED**.

## 2026-11-11 minute-tick guide optimization

**SOURCE IMPLEMENTED; ANDROID RUN UNVERIFIED:** Remove full virtualized-guide adapter rebinding from the 60-second timer; update only visible current-time lines. Suspend the timer entirely when the Activity is stopped or no EPG is loaded. Extend debug-only autonomous bitmap smoke coverage for marker movement and disappearance, requiring `debug-now-marker` in CI. Static source audit found all 14 CI assertion IDs represented and no new unresolved event references. Next: inspect actual Android CI/emulator results and instrument real device UI frame cadence, guide scroll behavior and PSS over long playback/recording sessions.

## 2026-11-11 bounded M3U metadata and XMLTV parser depth

- **Committed:** `PlaylistParser.parseExtinf` now extracts only the four consumed metadata keys instead of allocating a HashMap of all provider attributes per channel. Duplicate-key last-wins, mixed quoting, case-insensitive matching and existing SHA-256 channel IDs are preserved. Cap retained guide IDs at 256 characters (matching XMLTV IDs) and refuse channel logo URLs longer than 2,048 characters. Core regressions cover 300 legacy-style attribute cases and oversized metadata. **PASS:** isolated Kotlin 1.9.0 comparison of old/new attribute extraction on 25,005 deterministic fixtures.
- **Committed:** `GuideParser` rejects XML documents deeper than 64 elements, preventing pathological SAX nesting. Existing DTD and external-entity rejection remain unchanged. Added valid shallow and well-formed deep fixtures, and a **debug-only** in-app `debug-xmltv-depth-limit` check required by emulator CI. **PASS:** isolated JVM SAX handler accepts 14 and 64 levels and rejects 65 levels and DTD.
- These source changes add **no** dependencies, workers, background polls, network calls, or persistent caches. **UNVERIFIED:** full current Android Gradle build, emulator test job, actual Android SAX parser behavior, production memory/CPU improvement. Next: inspect exact push CI logs; fix compile/runtime regressions before any release readiness claim.

## 2026-11-12 indexed EPG window fast path — low resource usage

- **SOURCE IMPLEMENTED:** During `GuideSnapshot.prepareIndex()`, each channel schedule records whether it contains overlapping or invalid intervals. Non-overlapping schedules use existing binary-search boundaries and return the corresponding sublist without linearly checking every programme in the window. Overlapping and malformed schedules retain the original correctness-preserving filtered path. A subsequent optimization replaced the temporary Boolean with a nullable prefix-max array: non-overlapping schedules use their existing end timestamps as the binary-search index and allocate no LongArray; overlapping/invalid schedules retain the original prefix-max array. At 250,000 wholly non-overlapping programmes, avoiding 8 bytes per programme saves ~1.9 MiB of prefix-array payload. No secondary index, worker, timer or dependency.
- **REGRESSIONS COMMITTED:** Dense 12,000-programme, 750-window comparisons plus malformed/overlapping window assertions against naive filtering. **PASS local isolated JVM:** `kotlinc-jvm 1.9.0` compiled a standalone copy of the updated `GuideSnapshot` implementation, and a harness passed 20,750 comparisons (750 dense plus 20,000 randomized queries containing overlaps and invalid intervals). This is not a complete Gradle or Android device test. Full Android CI/emulator and physical-device performance remain **UNVERIFIED**.
- Next: inspect latest push-triggered GitHub Actions evidence; fix any Gradle/emulator failures before claiming Android readiness; measure guide lookup latency, PSS, and interaction responsiveness on low-RAM hardware.

## 2026-11-12 CI resources and unattended diagnostics

- **Committed** `0b12c9f`: documentation-only pushes no longer trigger APK/Android emulator jobs, avoiding wasted runner minutes, SDK downloads and repeated canceled builds. Pull requests and manual dispatch retain full checks; code changes on `main` continue to run normally.
- A failed emulator test script now captures a maximum of 1,200 Android logcat lines while the emulator is still available, and uploads them with the existing 14-day self-test evidence. No additional log is produced on success.
- **Local shell trap behavior PASS; GitHub CI execution UNVERIFIED.** Next verify actual current `main` build and emulator outcomes and use captured diagnostics to correct any failures at their root cause.

**2026-11-13 CI blocker:** Investigated failed Actions run 43305010954: APK build passed, emulator booted, but the runner split an inline Bash trap across separate `sh -c` invocations and failed before app tests. Move the complete QA harness into a single repository script, check Bash syntax in CI, and execute it as one emulator command. Preserve 2 GiB runner RAM and existing bounded diagnostics. Emulator acceptance remains pending an exact-commit passing run.

**2026-11-13 further XMLTV heap pressure:** Reuse one canonical guide-ID String per distinct ID during SAX import, bounded by the existing maximum channel count. Repeated programme IDs now share references; out-of-order declarations and cap behavior are covered by JVM tests. No global String.intern(), unbounded deduplication or new worker. Profile actual Android heap after CI.

**2026-11-13 emulator acceptance finding:** Full on-device self-tests now run (run 43353888148); 15 checks PASS and focus navigation FAIL on the touch-mode Pixel emulator. Adjust the debug D-pad probe to exit touch mode and focus the source view before directional traversal; capture a numeric failure mask to guide any further correction. Do not call the emulator gate PASS until the exact latest run succeeds. Actual hardware and streaming QA remain separate.

**2026-11-13 Android emulator validation closed:** [run 43354840233](https://github.com/DevTunaSoft/TunerOS/actions/runs/43354840233) PASS at exact code head `7094302` (unit tests + debug APK + emulator self-tests). In-app report: 16 PASS / 0 FAIL / 1 BLOCKED / 12 NOT RUN. Pixel touch-mode focus probe is corrected and verified; synthetic 160-channel/11,520-programme stress recorded 50,460 KiB process PSS and 429ms elapsed in one run. **DO NOT equate this with sustained or physical Android TV memory acceptance.** Prioritize real provider, 1–2 GiB RAM, remote-control, USB/SD, exact-alarm, reboot and DVR recording scenarios. Documentation-only pushes intentionally do not trigger CI.

**2026-11-13 long-running HLS capture:** Avoid `available.filter` and `listOf` candidate allocations on every manifest refresh; reuse the already bounded segment list via integer indices. Preserve ordered/live-edge/endlist and duplicate-sequence semantics with new core regressions. No extra recording buffer or background polling. Exact-commit CI/emulator and sustained DVR heap/stream acceptance remain gates.

**2026-11-13 acceptance progress:** [GitHub Actions 43472251052](https://github.com/DevTunaSoft/TunerOS/actions/runs/43472251052) **PASS** at `834a4cd`: Kotlin unit tests, debug APK, Android 35 emulator including a new synthetic HLS live-edge de-duplication test. App report: 17 PASS / 0 FAIL / 1 BLOCKED / 12 NOT RUN. Per-poll HLS candidate collections eliminated without changing bounded recording buffers. Real provider video/DVR capture, exact alarms, removable storage and sustained RAM/battery remain incomplete. Full evidence in `docs/ON_DEVICE_QA.md` and `docs/HANDOFF.md`.

**2026-11-13 SAF recording reliability:** Harden removable-storage capacity refresh to return zero on disconnected/uninspectable roots, return PARTIAL if the document cannot be renamed rather than treating an existing `.partial` file as complete, and enforce document-ID volume boundaries (no prefix false matches). Synthetic unit+Android QA added without allocating a new media buffer, thread or polling service. Android CI and physical USB/SD fault injection required to establish acceptance.

**2026-11-13 CI-confirmed SAF safety and deeper HLS Android QA:** [run 43478654869](https://github.com/DevTunaSoft/TunerOS/actions/runs/43478654869) PASS with 18 synthetic/local checks including fail-closed capacity/root policy. Extend the existing debug-only HLS integration probe to use production `AndroidHlsCaptureSource`, actual manifest parsing and the fixed 64 KiB segment copier against two tiny in-memory simulated responses; no network, saved media, or additional worker. New exact-commit emulator outcome pending. Physical SD/USB, provider and sustained RAM acceptance remain NOT RUN.

**2026-11-13 verified checkpoint:** [Actions run 43479669934](https://github.com/DevTunaSoft/TunerOS/actions/runs/43479669934) PASS on source `d583130`, validating the Kotlin core, APK and Android 35 emulator with 18 synthetic/local PASS, 0 FAIL, 1 BLOCKED, 12 NOT RUN. The on-device HLS test now runs the production Android parser and fixed-buffer copier against offline simulated HTTP inputs; SAF guard also passed. Real provider/USB/device RAM/battery acceptance remains open; full evidence in QA/handoff.

**2026-11-13 controlled HTTP-path acceptance:** Exercise the production Android HLS parser/capture engine over a strictly local, four-request, debug-only TCP fixture. Keep zero additional production services, a single 64 KiB recorder buffer and no new dependency. Pass/fail gated in existing emulator QA. Real provider playback, file-backed DVR, USB/SD and sustained hardware-resource tests remain open.

**2026-11-13 controlled TCP transport acceptance:** [GitHub Actions run 43520436790](https://github.com/DevTunaSoft/TunerOS/actions/runs/43520436790) attempt 2 PASS on `0e15f61`, 19 synthetic/local PASS / 0 FAIL / 1 blocked OS exact alarm / 12 NOT RUN. Debug-only localhost four-request HTTP server exercises the real Android HLS fetch and fixed 64 KiB segment copier. Attempt 1 failed an external Android SDK download (premature EOF); rerun of failed emulator job succeeded. No new production threads, services or dependencies. Next: scratch file-backed HLS capture, real video frame/codec and sustained device RAM with autonomous evidence.

**2026-11-14 file-backed HLS integration step:** Extend existing Android localhost HLS QA to create, close, verify via bounded reads and delete an actual app-private scratch file (2 media bytes; same 64 KiB capture buffer). Add JVM cancellation-after-first-segment regression to ensure partial bytes and last sequence remain accurate. Await exact-commit Android CI; production long-running DVR/storage/hardware limits unchanged.

**2026-11-14 internal DVR sink coverage:** A new debug-only existing-worker QA check writes through the production `InternalRecordingSink` using a unique private cache name, asserts protection from partial and completed filename collisions, verifies a successful rename and bounded 3-byte file read, and proves cleanup. App-module visibility was relaxed from `private` to `internal`; no runtime implementation changed. CI and physical acceptance pending, no extra worker/buffer.

**2026-11-14 verified DVR scratch storage acceptance:** [GitHub Actions run 43538188194](https://github.com/DevTunaSoft/TunerOS/actions/runs/43538188194) PASS at `3bfc34f`. Report: 20 PASS, 0 FAIL, 1 blocked exact-alarm permission, 12 NOT RUN. The existing debug-only HTTP HLS fixture now writes/reads/deletes an app-private recording file, and a second debug QA check validates the production internal sink's output counting, unique-name collision rejection, finalization rename and cleanup. The parent run for `ad66fe4` was cancelled by intentional CI concurrency; the latest combined commit passed. No additional production buffers, workers or dependencies. Physical target hardware, real provider playback/DVR, removable media and sustained PSS/battery remain outstanding.

**2026-11-14 HTTP DVR integrity gate:** Reject segment body length mismatches for identity HTTP `Content-Length` on clean EOF, preserving partial output instead of silently advancing HLS sequence. Four deterministic debug-only Android copier probes cover shortened, oversized, exact and unknown-length responses. Preserve one 64 KiB segment buffer and existing Android CI. Await exact-commit build/emulator evidence; physical streaming and sustained memory remain unverified.

**2026-11-14 verified HTTP segment integrity:** [GitHub Actions run 43540275329](https://github.com/DevTunaSoft/TunerOS/actions/runs/43540275329) PASS at `248d594` with 21 synthetic/local PASS, 0 FAIL, 1 blocked exact-alarm permission, and 12 NOT RUN. Reject known-length identity HLS segment EOF mismatches without any new allocation of media data or background work, retaining partial data. Android app QA proves shortened, oversized, matching and unknown-length response handling. Preserve the 64 KiB buffer. Next: controlled scheduled foreground DVR/recovery, real provider video and sustained low-memory performance acceptance.
