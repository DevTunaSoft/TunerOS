# TunerOS engineering handoff

## Repository and environment

Canonical repository: https://github.com/DevTunaSoft/TunerOS (main). Public and initially empty when inspected 2026-11-08.

This is a new native Android app, not a verified import of the unknown browser V8/V9 implementation.

## Architecture

- `core/`: pure Kotlin M3U/XMLTV models, bounded parsers and multiview policy. Kept free of Android dependencies for fast tests.
- `app/`: Android/Leanback launcher, URL import UI, searchable channel list and Media3 playback.
- `.github/workflows/android.yml`: intended CI builds and APK artifact after successful tests.
- `docs/`: roadmap, feature status, architecture status and handoff.

## Validation evidence

- Local command: `kotlinc core/src/main/kotlin/com/devtunasoft/tuneros/core/*.kt scripts/CoreSmoke.kt -include-runtime -d /tmp/tuneros-smoke.jar && java -jar /tmp/tuneros-smoke.jar`.
- Verified output: `CORE SMOKE PASS: M3U, XMLTV, multiview admission` (JVM Kotlin 1.9.0, Java 21).
- Local property/fuzz smoke: `kotlinc core/src/main/kotlin/com/devtunasoft/tuneros/core/*.kt scripts/FuzzSmoke.kt -include-runtime -d /tmp/tuneros-fuzz.jar && java -jar /tmp/tuneros-fuzz.jar`.
- Verified output: `FUZZ SMOKE PASS: 1000 playlists, 250 XXE attempts, 81 capacity combinations`.
- **PASS:** GitHub Actions run [42737228695](https://github.com/DevTunaSoft/TunerOS/actions/runs/42737228695) on commit `91ff70623fc0c0a3514faa19cb68743095a4fe80` completed `:core:test`, `:app:assembleDebug`, and artifact upload. Build tools: Gradle 8.9, Android SDK 35, JDK 17.
- **FAIL resolved:** Prior CI run 42508571401 failed at `MainActivity.kt:164` due to a cross-module nullable property smart cast. Fixed by local `let` binding in commit `91ff706`.
- **NOT RUN:** Physical device installation, actual live stream playback, Android TV remote focus, multiview, and release signing.
- Follow-on changes after the successful run require a new CI result before claiming build success for latest main.

## Known gaps and risks

- Alpha UI is deliberately minimal, not the final classic cable timeline.
- Only HTTP(S) M3U/XMLTV URL imports; no on-disk provider database or offline snapshots yet.
- XMLTV memory bounded by import bytes and program cap; an in-memory per-channel index avoids quadratic guide filtering, but guide storage needs paging for large feeds. New index/filter behavior requires CI regression verification.
- Channel identity based on tvg-id or stream URL; duplicate `tvg-id`s can collide across sources. Introduce provider-scoped stable IDs.
- Playback engine creates a new player per channel; test rapid switching and hardware decoder stress.
- HTTP imports permit cleartext transport for user feeds; warn about credentials and prefer HTTPS.
- HTTP redirect handling, gzip magic-byte detection and TV focus navigation require further regression tests.
- DRM content, subscriptions, EPG auto-matching, multiview decoding, secure source credential storage, release signing, and device acceptance are not implemented.
- Upstream licensing and stream distribution rights must be reviewed before copying source or distributing playlists.

## Next engineering tasks

1. Inspect GitHub Actions output, repair actual failures and pin tested tool versions.
2. Build Android APK, install and validate on real Android TV and mobile hardware.
3. Replace channel-list interface with TV-focused classic cable timeline and persistent indexed EPG.
4. Add safely persisted source configurations, provider-scoped channel identity, and fully bounded/network-safe imports.
5. Introduce playback failure diagnostics, recovery, stream format detection, and two-view selectable-audio controller.
6. Run property/fuzz tests, malformed M3U and XMLTV tests, fault injection, and app lifecycle tests.
7. Keep docs and acceptance evidence updated with every change.


## 2026-11-10 targeted stabilization after source audit

User-supplied review identified 11 findings. Rechecked the repository and implemented:
- Sorted per-channel EPG index with binary search and overlap-aware prefix max-end.
- Worker-computed current-program map with periodic refresh; virtualized Android ListView and debounced channel search.
- Timezone-less XMLTV timestamps now assumed UTC with a visible warning (provider-specific fallback zone can be supplied in code; UI setting still pending).
- XMLTV/playlist record caps now return partial imports and warnings rather than discarding the whole feed.
- Bounded M3U line reader (limit checked before allocating an unbounded line).
- Source URL encryption using Android Keystore AES-GCM; migration removes legacy plaintext preference keys. Hardware validation pending.
- HTTP downloader extracted into JVM core with HTTPS downgrade rejection, gzip magic sniffing, and decompressed size limit.
- Stream ID hash now includes source ID, guide ID, and URL, avoiding collisions when tvg-id repeats with different streams.
- Placeholder launcher icon and Android TV banner.
- Reproducible unit/randomized regression checks; CI concurrency cancels superseded same-branch builds.

**Outstanding:** Multiview playback and audio selection are not implemented. Gradle wrapper binary is absent; CI installs Gradle 8.9. Android device acceptance, UI performance benchmarks, and real-network fault injection have not run.

**Testing integrity:** `scripts/FuzzSmoke.kt` is a *seeded randomized smoke script*, not a coverage-guided fuzzer. Its 250 XXE iterations used one payload template with changing IDs. New JUnit regression tests cover download redirects, gzip detection, byte caps, timezone fallback, cap warnings, duplicate guide IDs, overlapping schedules and bounded lines. `RandomizedRegressionTest.kt` adds seeded property checks, but does not replace real fuzzing.

**CI baseline before stabilization:** [run 42737399473](https://github.com/DevTunaSoft/TunerOS/actions/runs/42737399473) PASS. Intermediate stabilization runs failed on an outdated cap test and a UTC warning label; root causes were corrected. Check the latest Actions run for the definitive status of these changes.

**Recovery:** From the repository root run `gradle --no-daemon :core:test :app:assembleDebug` using JDK 17, Gradle 8.9 and Android SDK 35. The debug APK is under `app/build/outputs/apk/debug/` on success. GitHub Actions uploads the APK only after success. Do not post private source URLs or credentials in public issues.

**Next:** Confirm CI green; investigate failures without duplicate retries. Then add instrumentation/performance acceptance, secure-source migration device tests, and the full TV guide. Keep FastChannels serving deferred.


## Stabilization CI checkpoint — 2026-11-10

**PASS:** [Actions run 42738309789](https://github.com/DevTunaSoft/TunerOS/actions/runs/42738309789), commit `eb0acc68e192e1589a6ace71153d711248d39bc0`: `:core:test`, `:app:assembleDebug`, and APK artifact upload completed. Includes new core regressions and Android source changes. **NOT RUN:** real-device acceptance, Windows bootstrap, performance benchmarks.

The optional `scripts/build.ps1` helper downloads Gradle 8.9 and validates its SHA-256 checksum from the official Gradle distribution endpoint before running tests/build. It is not a standard Gradle wrapper, and is **UNVERIFIED on Windows**. Gradle's official wrapper JAR remains an open repository task.


## Optional XMLTV window-retention implementation (branch: perf/windowed-epg-retention)

- Parser API now accepts `GuideWindow(fromEpochSeconds, toEpochSeconds)`, an exclusive-end retention range. A programme is retained if it overlaps the range.
- Optional `includeDescriptions = false` avoids keeping XMLTV descriptions. Both new options preserve legacy behavior by default, so existing app call sites remain unchanged.
- Window-excluded programmes do not consume the retained-programme cap. Timezone and invalid-timestamp warnings are still accumulated from the full XML input.
- Four new core regression tests cover window boundaries/overlaps, cap ordering, description opt-out, and invalid windows.
- **UNVERIFIED:** Android app does not yet supply a window or opt out of descriptions, so no device memory saving is claimed. JVM tests and APK packaging must pass on this branch before merge. Performance validation against 1 GB and 2 GB Android TV profiles remains NOT RUN.
- **Next:** Confirm CI for the PR, capture an Android device baseline, and wire a configured moving window into `loadGuide` with a clearly documented refresh/resume policy. Test that hide-no-EPG still means “has schedule” and not merely “playing now.” A too-short window could hide channels with future-only schedules, so product semantics must be preserved.


## Follow-up hardening checkpoint — 2026-11-10

The code audit identified critical correctness defects; fixes are committed to the same draft PR to avoid duplicate work.

- **Fixed in source:** Legacy plaintext URL survives failed Keystore encryption/migration. If an encrypted value cannot be decrypted, an existing legacy URL is returned instead of discarded. Missing UI warning for failed startup migration remains an outstanding usability task.
- **Fixed in source:** Keystore decrypt/key generation during startup and encrypted URL saves during import now execute on the existing single-thread import worker. UI fields are populated on the main thread only if still empty, avoiding overwriting text typed during startup.
- **Fixed in source:** SourceDownloader now implements `skip()` through its size-counted `read()` path, with regression cases for cap bypass and EOF.
- **Cleaned up:** XMLTV fallback timestamp regex and SAX capture limit are shared constants.
- **UNVERIFIED:** No Android device test, Gradle build, CI verification, or measured memory benchmark was executed in this session. Changes require CI and device acceptance before merging.
- **Still open:** Single player reuse/release-on-stop, measured player buffering defaults, real EPG memory baseline, optional retention-window integration, channel model memory reduction, potential future persisted guide database, live D-pad focus and vector banner acceptance.
- **Integration safety:** Optional guide window and description omission remain opt-in; live Android calls retain their prior full-guide behavior. Be careful not to remove future-only EPG channels when enabling a short window.

- **Playlist allocation:** The M3U parser now reuses one SHA-256 digest per parse and pools identical request-header strings for the duration of the parse. This is source-only optimization; heap savings have not been measured and tests have not run on the updated branch.


## DVR/PVR development checkpoint — 2026-11-10

Current branch `perf/windowed-epg-retention` now contains a bounded DVR foundation:

- Pure-core `StreamRecorder` copies network media directly to storage with a fixed 64 KiB default buffer, free-space reserve checks, cancellation and byte progress. HLS reuses one caller-owned segment buffer instead of allocating a buffer per segment.
- Android `DvrRecordingService` is a `mediaPlayback` foreground service. It records without decoding/re-encoding, preserves partial files, throttles catalog progress writes, blocks HTTPS downgrades, honors provider User-Agent/Referer headers, and permits only one active recorder.
- `RecordingCatalog` is built-in SQLite with bounded recent queries. Source stream URLs are not persisted there. Stale in-progress rows recover as partial after process interruption.
- `RecordingScheduleStore` persists future job metadata separately. Source URLs and optional provider headers use `SecureSourceStore`/Android Keystore. Alarm scheduling uses exact alarms when allowed; missing exact-alarm access leaves the job persisted but unarmed rather than pretending an inexact background start is reliable. Boot and permission-change receivers only re-arm schedules.
- The UI supports current Record Now, future guide scheduling, cancellation of a focused future schedule, an Exact DVR timing settings path, Stop recording, and a bounded/recycled recent recordings list with local Media3 playback for completed files.
- HLS core parsing and capture are bounded. Supported initial subset: unencrypted MPEG-TS media playlists, master variant selection when A/V are contained in the selected variant, media-sequence/live-edge tracking, byte ranges, redirects and stalled-playlist detection. Sequence gaps stop as partial. Encrypted HLS, fMP4 `EXT-X-MAP`, separate external audio/video rendition capture, and DASH recording fail closed.
- Latest verified pre-integration checkpoints include Actions runs 42842154373 (exact-alarm scheduler fix), 42842606376 (bounded HLS parser tests), and 42843062838 (recording header propagation). The final Android HLS integration and subsequent hardening require the latest branch CI result before being called PASS.
- **UNVERIFIED / NOT RUN:** real Android TV/mobile recording, long-duration memory measurement, reboot-to-scheduled-recording acceptance, provider-specific HLS behavior, USB/SAF destinations, storage removal, and live fault injection.


### SAF / SD / USB recording storage checkpoint — 2026-11-10

- `RecordingDestinationStore` persists the selected tree URI only; credentials are not involved.
- `SafStorageInspector` currently accepts Android's local ExternalStorageProvider only, verifies that the selected tree is writable, and requires a reported `COLUMN_AVAILABLE_BYTES` value. TunerOS does not disable the 512 MiB reserve when free space is unknown.
- `RecordingSink` is now the sole storage abstraction for the foreground recorder. Raw and HLS capture share the same sink path: internal app storage or a SAF document tree. The HLS/media buffers remain unchanged and bounded.
- SAF output rechecks free-space approximately every 16 MiB while conservatively subtracting bytes written between provider queries. A removed drive causes writes/finalization to fail; the recorder does not silently fall back to internal storage.
- The catalog's legacy SQL column name `file_path` is retained for compatibility, but Kotlin now treats it as a generic `storageLocation` which can be either an internal absolute path or a SAF content URI.
- `RecordingLocationAccess` validates internal canonical paths and SAF content URIs against persisted tree grants before playback/deletion. An unavailable external drive is distinct from a missing document so the catalog row is not erased just because the drive is unplugged.
- The UI can choose a recording tree, return future recordings to internal storage, play/delete existing internal or external recordings, and keeps prior persisted grants so recordings on previously selected media remain accessible.
- Automated evidence: GitHub Actions run **42848491493** completed successfully on code head `48a96c6709a05d5de738c9b983541426f6b51635`, including core tests, debug APK assembly and artifact upload.
- **UNVERIFIED / NOT RUN:** selecting an actual removable SD/USB device on target Android hardware, long recording to removable media, removal during capture, reinsertion and playback, provider-specific rename behavior, and storage-pressure behavior on a real device.


## 2026-11-11 PR integration audit and bounded-resource fixes

- Audited live `main` (`b78d42b`) and PR #1: despite its original narrow title, it contains 37 changed files covering the guide timeline and experimental DVR/PVR. Avoid duplicating these implementations on a second branch.
- HLS capture polling: clamp target duration to 2–20 seconds **before** multiplication to avoid overflow and rapid network polling from malformed manifests. Added `extremeTargetDurationUsesBoundedPollingInterval` JVM regression (commit `5b36ff2`).
- Scheduled DVR recovery: replace the first-200-jobs-only query with stable keyset pagination (`start_epoch,id`) and process all pending jobs in bounded batches of 200. This resolves a silent omission of later schedules while keeping memory bounded; Android SQLite/reboot acceptance is **NOT RUN**.
- UI: collect the nine DVR actions in one horizontally scrollable toolbar so they do not crowd out the video/guide on shorter displays. TV focus/remote behavior remains **UNVERIFIED**.
- Android guide import now uses `includeDescriptions = false`. Programme descriptions are not used by the current alpha UI, so omitting them avoids retaining strings; actual heap savings are **UNMEASURED**. Windowed retention remains disabled at the app call site to avoid hiding channels with only future schedules.
- Integration gate: require successful CI unit tests and debug APK assembly for the final branch head before merging. Physical-device playback, SAF/USB recording, and reboot scheduling require subsequent acceptance; these are not implied by a green CI result.


## 2026-11-11 verified main integration and next hardening

- **PASS:** PR #1 merged into `main` at commit `d618c91d56f1118835c0e6b6b588d45a2a89e605`. GitHub Actions [run 42972145588](https://github.com/DevTunaSoft/TunerOS/actions/runs/42972145588) completed `:core:test`, `:app:assembleDebug`, and debug APK artifact upload.
- **PASS (earlier PR):** [run 42971947481](https://github.com/DevTunaSoft/TunerOS/actions/runs/42971947481) at `7147746` completed the same CI targets before merge.
- The broad merged feature set includes classic cable timeline and an **experimental** one-recorder DVR with persistent schedule/library, SAF recording destinations, and the bounded optimizations documented above. No on-device or long-running DVR certification is claimed.
- Duplicate recording IDs now fail without replacing existing catalog rows; internal media output claims a fresh partial file and refuses to overwrite a prior completion. Cleanup refuses to delete files the current attempt did not create. `tryInsertStart` is the collision-safe catalog API.
- **Next code change (pending CI at time of writing):** Detect short/oversized HTTP HLS byte-range segment responses in the pure core capture engine; mark capture as a partial/gap and add a focused JVM regression. This prevents silent acceptance of wrong byte counts, but does not yet validate HTTP `Content-Range` headers on Android.
- **NOT RUN:** Android TV and phone acceptance, remote focus, actual HLS and SAF USB/SD capture, reboot after >200 scheduled jobs, memory profiling under 1/2 GiB, Windows helper, and release signing.
- Next steps: 1) confirm CI on the newest `main` commit, 2) hardware acceptance/logs, 3) Android HTTP range header validation and recorder fault tests, 4) Stream Doctor actionable diagnostics without credential leakage, 5) memory benchmarks before enabling XMLTV window pruning.

## 2026-11-11 HTTP HLS range integrity hardening

- **PASS**: [GitHub Actions run 42972485921](https://github.com/DevTunaSoft/TunerOS/actions/runs/42972485921) on `main` commit `8445430`: `:core:test`, `:app:assembleDebug`, and debug APK artifact upload. Includes the short HLS byte-range regression.
- **New change, CI PENDING**: Pure Kotlin `HlsContentRange.matches` validates 206 `Content-Range` offsets/end and reported length boundaries, guarding against a provider returning the correct byte count from the wrong offset. `DvrRecordingService` rejects mismatched or absent headers and disconnects the connection; focused JVM tests cover positive, missing, corrupt, overflow and wrong-range responses.
- **UNVERIFIED**: Hardware provider compatibility with strict `Content-Range`, Android TV/network fault injection, USB/SD recording, and actual segment playback. Correctness takes priority over silently accepting malformed providers.


## Final verified source checkpoint — 2026-11-11

- **PASS (CI):** [GitHub Actions run 42972740969](https://github.com/DevTunaSoft/TunerOS/actions/runs/42972740969), exact code head `26953c0d2c5c09b6b849e6760836e2ebeb65b762`, completed `:core:test`, `:app:assembleDebug` and artifact upload. Artifact `TunerOS-debug-apk` was confirmed present (4,726,454-byte ZIP); no real-device validation was run.
- The new core `HlsContentRange` tests cover correct ranges, wrong offsets, oversized responses, invalid reported totals, malformed headers and overflow. The Android recorder requires matching `206 Content-Range` headers before accepting requested byte ranges.
- **NOT RUN**: Real streaming playback, Samsung/Android TV lifecycle/focus, timed 1 GiB/2 GiB memory profiling, long-running recordings, scheduled reboot jobs, SAF removable storage, Windows script and release signing. Treat the new DVR as experimental.
- This Handoff remains the authoritative verification ledger. The roadmap defines separate non-overlapping workstreams; the current environment supports GitHub edits and CI but did not dispatch independent coding subagents. Avoid duplicate branches or PRs.


## 2026-11-11 Stream Doctor first implementation (CI verified)

- New `core/StreamDoctor.kt` classifies Media3 numeric playback errors into short static diagnostic text (network, timeouts, provider access/limits, media format, device decoder, DRM and fallback). The official code reference is https://developer.android.com/reference/androidx/media3/common/PlaybackException.
- `MainActivity` now uses the same helper for live-channel and recording playback failure messages. It does **not** include exception messages, network URLs, user agents, referrers, tokens or provider headers.
- `StreamDoctorTest.kt` exercises classification and bounds/privacy properties. The classifier creates no background work and retains no error history; it only allocates the returned brief message when a player error occurs.
- **PASS (CI):** [GitHub Actions run 42973394085](https://github.com/DevTunaSoft/TunerOS/actions/runs/42973394085), commit `2e9f612674fe814108e3dc3f6b39bfae5b6bb396`, completed core tests, debug APK compilation, and artifact upload. **NOT RUN:** live Android TV or phone troubleshooting, Media3 device playback, memory profiling. This is a static code-to-advice classifier, not an automatic recovery agent.

## 2026-11-11 EPG/player-memory and Stream Doctor checkpoint

- **PASS (CI):** [run 42974523335](https://github.com/DevTunaSoft/TunerOS/actions/runs/42974523335), source `bce2c3d`: guide index groups channel programmes once, sorts grouped lists in-place instead of copying them, binary-searches monotonic prefix end-times for window queries, and removes pair-list allocations from current-program snapshots. Seeded randomized overlaps/out-of-order/future-only regression tests were included. Do not claim measured device RAM savings.
- **PASS (CI):** [run 42974773677](https://github.com/DevTunaSoft/TunerOS/actions/runs/42974773677), source `e7fd69c`: live Media3 player reuse for compatible provider request headers, releases player when headers differ and when activity stops. Remote/rapid-switch stress tests were **NOT RUN** on hardware.
- **This follow-up change, CI PENDING:** Align Stream Doctor classification with documented Media3 error constants, including separate premium/account/parental failures and audio-track errors. Reference: https://developer.android.com/reference/androidx/media3/common/PlaybackException. Core tests verify exact mappings and bounded privacy-safe messages. No network, credentials or dynamic exception messages are passed to diagnostics.
- **NEXT:** confirm latest CI, perform Android TV/phone acceptance and actual 1/2 GiB memory tests; identify playback/decoder regressions before implementing larger multiview features.

## 2026-11-11 memory/reliability hardening checkpoint

- **PASS (CI):** [run 42975413467](https://github.com/DevTunaSoft/TunerOS/actions/runs/42975413467), commit `2b28494`: XMLTV SAX capture limits of 256-character programme titles and channel names and 2,048-character descriptions (descriptions disabled in current Android UI). Parser warns when truncation occurs; oversized-field JVM regressions passed and debug APK artifact uploaded. **UNVERIFIED:** real-device heap reduction.
- **Source-only, latest CI pending:** DVR alarm re-arm now reuses one `RecordingScheduleStore` for all bounded 200-item pages and catches per-alarm exceptions, preserving progress on unaffected jobs. A missing exact-alarm permission no longer builds a PendingIntent. **NOT RUN:** reboot/system permission revocation and >200-schedule hardware tests; a CI build does not verify AlarmManager operation on Android.

## 2026-11-11 raw DVR no-progress hardening

- A custom or malfunctioning input stream can return zero bytes indefinitely. The previous `StreamRecorder.copy` loop would spin without a pause, potentially consuming a full CPU core and battery. The bounded recorder now throws `IOException("Input stream made no progress")` after 16 consecutive empty reads, resets the counter on a successful read, and still honors cancellation and free-space checks each iteration. This adds no worker threads, polling timers, or extra buffer allocations.
- New `StreamRecorderTest` exercises permanent zero-read, intermittent progress and cancellation. **CI PENDING** for this exact change. Android TV hardware and real recording acceptance remain **NOT RUN**.

## Verified consolidated checkpoint — 2026-11-11 (source head 53d5b7a)

- **PASS:** [GitHub Actions 42976022983](https://github.com/DevTunaSoft/TunerOS/actions/runs/42976022983), exact main commit `53d5b7a54f2941c82e12cbaccb2f959a3c7c42f3`: `:core:test`, `:app:assembleDebug`, and artifact upload completed.
- **EPG:** `bce2c3d` in-place channel group sorting, binary overlap-window lookup, fewer temporary pairs; `5897233` removes 500-row display cap and caches filtering so all imported channels (up to 25,000) are browsable. Reproducible unit tests PASS.
- **XMLTV:** `2b28494` bounded retained title/name/description strings and truncation warnings; optional descriptions disabled for the Android guide. JVM oversized-text regressions PASS.
- **M3U:** `53d5b7a` caps retained dedup IDs at accepted channels and header-string pooling at 512 unique values. Duplicates/overflow regression tests PASS.
- **Playback:** `e7fd69c` reuses live Media3 player across compatible headers, releasing on header change or Activity stop; `f7c14bd` aligns Stream Doctor messages with official error codes. CI PASS, device switching UNVERIFIED.
- **DVR:** `c431c97` reuses one schedule SQLite helper while rearming and isolates individual alarm failures; `69d30ee` guards against zero-byte read CPU spins. Both changes are present in latest passing main build. Device reboot and recording acceptance NOT RUN.
- **LIMIT:** These are code/CI claims only. No emulator/device, memory or battery benchmark, 2,361-channel UI scroll acceptance, USB/SD capture, boot scheduling, live provider playback, or production release validation was executed in this environment. Do not infer RAM reduction numerically without measurement.
- **NEXT:** Verify Android lifecycle, focus, large-guide import and memory; perform real DVR capture/recovery and release validation before widening multiview or DVR format support. Keep one main branch and no duplicate PRs.

## 2026-11-11 Android XMLTV parser portability and security

- Existing `GuideParser.parse` demanded Xerces-specific `disallow-doctype-decl`, `load-external-dtd`, `FEATURE_SECURE_PROCESSING` and explicit `isXIncludeAware=false` from the SAX factory. Android Expat does not recognize several of those features; Android JAXP can throw `UnsupportedOperationException` for `setXIncludeAware`. This can prevent *every* XMLTV import even for ordinary guides. Android platform API/device behavior still requires validation.
- Switched to a streaming SAX `XMLReader` using the standard lexical-handler property. The parser **must** support this property, or import fails closed. Its `startDTD` callback throws for all DOCTYPE declarations, and both external entity resolver overloads throw for external references. XInclude stays disabled by default. No DOM tree, extra parser library, or full-document retention was introduced.
- Added `GuideSaxSecurityTest`: ordinary guide with XML entities, external DTD, internal entity expansion, and nested expansions. JVM test/build evidence belongs to the GitHub Actions run for this exact commit; Android TV and phone acceptance remains **NOT RUN**.

## 2026-11-11 low-allocation guide timeline checkpoint

- The indexed guide already returns programmes sorted by start/end. Previously `CableGuideAdapter` passed those entries to `TimelineLayout.row`, which filtered, sorted, and copied them again for **every visible row** on each refresh or guide shift.
- The cable adapter now uses `TimelineLayout.rowFromSorted`, a single-pass renderer that creates only the required clipped timeline cells. The original generic `row` method retains unsorted-input semantics for other callers. Allocation pre-sizing is capped at 64 cells when most overlapping programmes will be discarded.
- Added randomized parity tests comparing indexed/fast-row output to the generic implementation, plus expired and overlap edge cases. **CI pending for this exact commit**. RAM allocations were not device-profiled; no numerical memory saving claimed.

## 2026-11-11 M3U import transient-allocation checkpoint

- Imported channel IDs were already stable SHA-256 prefixes, but generating each hex string allocated at least 12 formatter result strings (plus formatting machinery) per channel. This was repeated up to 25,000 times per import.
- The new encoder writes the first 12 digest bytes into one 24-character buffer, preserving exact historical IDs, case, and collisions semantics; HTTP(S) URL scheme checks no longer construct a temporary set or lowercase scheme string.
- `PlaylistStableIdTest` checks original SHA-256 hex equality, provider separation, uppercase protocol acceptance and reproducibility across two 1,000-channel imports. **CI pending** for exact commit; no empirical heap or CPU claim until instrumented device profiling.

## 2026-11-11 Autonomous on-device QA foundation (source integration)

- The new `OnDeviceSelfTest.kt` executes synthetic M3U/XMLTV parsing, DTD rejection, timeline/filter correctness, 1 KiB direct-to-disk scratch DVR copying, Android Keystore canary encryption, alarm capability and optional external-storage checks **from inside TunerOS**. It uses the existing single-threaded Activity worker; no extra background executor, diagnostic polling loop, provider network request, or active recording mutation is introduced.
- Startup and post-import QA runs automatically. Overlapping requests are coalesced with a dirty flag so results are not silently lost. A persistent run marker detects an interrupted previous check pass, while latest-report storage and a max-32 KiB event journal bound device storage/RAM. Only numeric values and static event names are retained; source URLs, tokens, titles and provider error bodies are excluded. Explicit user action is required to share.
- The UI exposes `Self-test report` in the DVR action strip. Normal imports log counts, monotonic duration and PSS snapshots. Media3 playback logs request/ready/isPlaying, first rendered video frame, error code and a one-shot 30-second timeout using the existing main-thread Handler. DVR logs start/completion/partial/failure/cancellation and interrupted catalog rows on the recorder's existing executor. All diagnostic writes in the critical recording path are best-effort and caught so they cannot prevent recording finalization.
- New source work was committed directly to `main` across commits `0f5b38b` (runner), `0a1778e` (Activity UI), `50edf79` (extra assertions), `b9b077a` (coalescing), `a56c673` (serialization), `0f84de1` (playback instrumentation), and `84b0912` (DVR instrumentation), with supporting updates in between.
- **SOURCE IMPLEMENTED; CI UNVERIFIED FOR THE NEW QA COMMITS; DEVICE RUNTIME NOT RUN.** Earlier successful CI on `53d5b7a` does not prove that the new diagnostic code compiles or passes on Android. GitHub Actions push status is not accessible through the currently available repository status API; do not infer a pass.
- **NEXT:** Confirm latest Android APK CI build, then have the app autonomously execute instrumented remote/rotation UI tests, scheduled alarm/reboot evidence, USB/SD loss/recovery probes, and bounded 1/2 GiB memory/CPU/battery stress. Environmental power cuts, forced USB unplugging or restricted permission grants cannot be independently caused by an ordinary Android application; leave these NOT RUN/BLOCKED unless an authorized test rig executes them. No user manual QA steps.

## 2026-11-11 Autonomous Android CI + DVR coverage checkpoint

- The app-side diagnostic system now checks SQLite catalog insertion, duplicate-ID refusal, protection, progress and interrupted-row recovery in a separate disposable `tuneros-selftest-recordings.db`. The production `recordings.db` is never used for this synthetic test.
- Real DVR event evidence includes scheduled alarm re-arm exact/blocked/failure counts, actual alarm delivery and foreground service request, and recorded-media playback request/ready/playing/first-frame/error/timeout. Live playback rebuffering after READY now receives a one-shot 30-second timeout, without periodic diagnostic polling.
- Updated GitHub Actions workflow adds `emulator-self-test` after the existing core-unit/debug-build job. It boots Android 35 with 2 GiB RAM and a 256 MiB heap, launches the app, reads the app's own debug report with `adb shell run-as`, and fails the job if required synthetic checks are absent or FAIL. Artifacts include the self-test report.
- **CODE COMMITTED TO MAIN; NEW CI JOB UNVERIFIED; PHYSICAL-DEVICE QA NOT RUN.** CI status tools currently expose no workflow-run result for this new commit. Do not mistake self-test wiring for a successful emulator run.
- Next task: inspect actual emulator job outcome and correct any Gradle/SDK/runtime failures, then add a bounded no-provider EPG load/stress acceptance probe and autonomous remote-navigation instrumentation.

## 2026-11-11 static consistency checkpoint

- **PASS (static source consistency, not compilation):** re-fetched six changed files from `main` and checked all 32 `OnDeviceSelfTest.Event` references against the 29 declared event types; **0 unresolved**. Eight Android emulator assertions match eight source test names exactly. Only one emulator CI job was found. The catalog uses a separate `tuneros-selftest-recordings.db`, and the QA component has no polling loop or new thread/executor. This check did **not** execute Kotlin/Gradle, boot an emulator, or record memory usage.
- Latest QA work includes commits `81c48b1` (emulator CI), `870db5d` (SQLite in-app test), `5491716` (post-rearm report), and `0c706ef` (explicit INCOMPLETE when release scenarios remain unverified). All were committed to `main` without another branch or PR.
- **CI/emulator runtime result UNVERIFIED.** The current GitHub connector exposes no branch-push workflow run listing or corresponding check result; do not claim the new job passed or failed. External console and SDK were unavailable here. Next: inspect automated CI's Android emulator report, repair any actual failure, then extend in-app synthetic large-guide stress while retaining conservative resource limits.

## 2026-11-11 continuation — autonomous guide stress / UI focus / report hardening

- Baseline inspected: `main` at `a277af4`, no open issues/PRs reported. Follow-up code merged directly into `main` without new branches: `8b6d0e7` (bounded guide stress), `7728341` (in-app optional stress result/focus evidence), `291d24c` (debug-only control focus probe), `d4d6d3e` (CI launch/assertions), `aaa4cdb` (short event I/O lock and atomic report publication), `2ff6cea` (CI avoids an in-progress report).
- Synthetic fixture is 160 channels / 11,520 programmes, exercises indexed lookup/window/timeline, search/filter, records wall/CPU/PSS/heap samples, and has 10s / 32 MiB sampled-heap-growth smoke budgets. Only explicit debuggable `tuneros.debug.qa` intent launches it; normal users do not incur benchmark allocations or automated focus movements.
- Existing single-thread worker is reused; no new watchdog/service/polling thread. Diagnostic event writes now synchronize on their own short I/O lock instead of blocking on the entire synchronized self-test. `OnDeviceSelfTest.run` atomically publishes its report after completion. App-side focus probe checks basic focus traversal, **not complete D-pad remote navigation**.
- **PASS (static review only):** six changed Kotlin/workflow files re-fetched; 34 event references all resolve to 31 event types; required ten emulator check IDs all exist; focus/stress are debug gated; event lock separation and atomic report marker checks present; all five edited Kotlin file brace counts match. **NOT RUN / UNVERIFIED:** Gradle compilation, actual GitHub Actions emulator outcomes, Android TV hardware and memory certification. GitHub connector exposes no push-triggered workflow-run list for these commits, and local runtime lacks Android SDK/network access. No CI success claimed.
- **Next unblocked task:** inspect actual automated emulator logs; correct any compile/runtime failures, then implement an isolated in-app key-navigation/rotation lifecycle harness and build 1 GiB-target RAM acceptance metrics with no activity during normal user sessions.

## 2026-11-11 autonomous development checkpoint — TV guide boundary focus

- Reconciled baseline against current `main` at `3efc8d0`, confirmed no open PRs/issues. No duplicate implementation found in `TimelineFocusPolicy` or `ProgramTimelineView`.
- Root cause: `ProgramTimelineView.moveSelection(-1)` returned false when its selected programme was already the leftmost selectable item, causing the Android focus dispatcher to leave the timeline rather than shift the guide backwards. On a window shift, `preferredIndex()` fell back to the *first* available programme without direction awareness.
- Fix committed to `main`: `a49d689` adds `TimelineFocusPolicy.shiftedIndex` with O(visible row) linear scan, no new collections; `8358fb9` makes timeline boundaries shift in both directions and preserves pending direction across the row rebind; `b9a1bdc` and `c3c4fd7` add fixed and deterministic randomized JVM regressions; `7638670` extends existing autonomous `offline-timeline` assertions. No new dependencies, background tasks, media decoders, worker threads or UI views.
- Verification **PASS**: direct Kotlin CLI compilation and local isolated test harness executed 12 fixed assertions plus 50,000 randomized fixtures × 2 directions = 100,000 directional cases. One false test expectation and then a real helper fallback bug were detected and corrected *before* committing the implementation. These tests verify the pure Kotlin policy with a minimal model stub, **not** Android view dispatch behavior or exact Gradle build artifacts.
- Verification **UNVERIFIED**: latest `main` compilation in Android Gradle CI, emulator and physical TV remote. GitHub connector provided no push workflow-run evidence and the local runtime has no Android SDK; don't claim a passing APK for these commits.
- Next: consume exact-commit CI output, then independently automate genuine Android D-pad event/focus and activity-rotation acceptance on device/emulator while keeping tests debug-only and avoiding unnecessary playback, recording disruption, polling or allocations.

## 2026-11-11 low-memory coding continuation

- Baseline re-audited before editing: `main` at `4c1a346` with no open PRs or issues. Changes committed directly to `main` without separate branches.
- Corrected a real source defect in `ProgramTimelineView`: an empty EPG row became non-focusable, so remote LEFT/RIGHT could not cross an empty programme window. Empty rows now remain focusable but non-clickable. Added a debug-only synthetic `ProgramTimelineView` key-handler probe in `MainActivity` with a private `OnDeviceSelfTest` event and required CI assertion (`debug-empty-guide-dpad`). It checks both directions without touching user channels, player, network, storage or schedules.
- Optimized common EPG lookup: `GuideSnapshot.programsBetween` returns a sublist view for entries that all intersect the time window, avoiding an unnecessary extra filtered-list allocation during rendered guide row refresh. Overlap/expired records still filter. Added fixed and deterministic randomized `CoreTest` regressions; extended in-app `offline-timeline` probe.
- Reduced redundant startup QA: `RecordingScheduler.rearmPending` now returns whether it visited any pending schedules. `MainActivity` reruns QA only if a re-arm actually visited jobs, preventing a duplicate Keystore/parser/SQLite test pass on most startups.
- **PASS:** compiled an isolated copy of the core indexed programme window algorithm using local `kotlinc-jvm 1.9.0` and executed **40,000 randomized interval comparisons** against a naive sorted/reference filter (5000 fixtures × 8 queries); no mismatches. This is an isolated JVM algorithm check, **not** an APK build or complete repository CI.
- **STATIC CHECK:** GitHub connector updates returned successful commit SHAs; latest exact-commit CI results were not available via the connected workflow-run lookup, and publicly fetching the Actions UI failed. **UNVERIFIED:** Android Gradle compilation, debug focus UI execution, emulator and physical remote navigation. No production-ready claim is made.
- Next: inspect actual push-triggered CI status, debug any build/emulator exceptions, then expand on-device autonomous lifecycle and focus traversal acceptance without memory growth or disturbing playback.

## 2026-11-11 continuation — encrypted source recovery and import safety

- **Baseline:** `main` at `9bf4cdb`; fetched current `MainActivity.kt`, `OnDeviceSelfTest.kt`, guide, timeline, roadmap and CI files. No open PRs/issues returned. No existing cold-process encrypted-source restore path was found, despite activity configuration retention.
- **Root cause 1:** Existing `onRetainNonConfigurationInstance` restored full in-memory guide/channels only on configuration changes, not after process termination. On restart, the app populated encrypted saved URL fields but did not load either source. Implemented low-memory recovery using those stored URLs on its existing single worker, with epoch checks so user actions win. `onSaveInstanceState` saves only search query and guide-window timestamp, not guide data, playlist data or provider credentials.
- **Root cause 2:** Previously, new feed URLs were saved to Keystore *before* confirming successful download and parse, risking loss of the previously working source. Imports now save only on successful, nonempty parsing and only for the latest import epoch; empty feeds preserve current guide/channels and the old encrypted URL. Auto-restore imports do not re-encrypt unchanged URLs.
- **Files:** `app/.../MainActivity.kt`, new `core/.../SourceRecoveryPolicy.kt`, `core/.../CoreTest.kt`, `app/.../OnDeviceSelfTest.kt`, `.github/workflows/android.yml`. Commits include `5485580` (safe source updates), `9f2b306` (shared importer), `0d2673f` (low-memory restore), `3e665c0` (empty and stale validation), `68a8fbb` (pure policy), `553789d` (policy wiring), `8ba5eec` (unit regressions), `ee2a009` (in-app checks), `63e839a` (emulator CI assertion).
- **PASS:** Local isolated Kotlin 1.9.0 compiler and JVM test harness executed 5,208 recovery-policy assertions, including source mismatch, active import, already-loaded data, missing saved source, and all current/stale epoch combinations. Re-fetched seven source/CI/docs files; source/CI references match and brace balance static checks passed. The isolation harness compiled the exact policy *behavior*, not the entire Android application.
- **UNVERIFIED:** Full Android Gradle compilation, emulator run, activity lifecycle restoration on a physical phone/TV, live encrypted-source recovery over HTTP(S), and source-import failure in production. The connected GitHub workflow listing is limited for `main` push runs; no new successful run was observed. Do not claim PASS for these.
- **Next task:** acquire actual latest Android CI/emulator logs; fix failures at root cause; continue autonomous, bounded lifecycle testing without new threads, providers, playback disturbance, or large saved-state Bundles.

## 2026-11-11 source-recovery QA and recycler continuation

- Source recovery policy was compiled with local Kotlin/JVM 1.9.0 and executed with **5,208 assertions PASS**, including null/edited/loaded/active saved-source cases and exhaustive request/latest epoch combinations from -25 to +25. This test validates only the small pure Kotlin policy; it does not compile Android or execute provider downloads.
- `MainActivity.kt` search input now has `InputFilter.LengthFilter(256)`, and `onSaveInstanceState` saves at most 256 query characters plus one `Long` time window. If Android supplies no saved instance state after a cold launch, the encrypted feed URLs still reload but the search/time position resets. No playlist, XMLTV programmes or URLs are serialized into the Bundle.
- Root cause: recycled `ProgramTimelineView` could inherit a pending LEFT/RIGHT shift from the previous channel. The view now applies the pending direction only when the rebound channel ID matches; otherwise it clears the pending direction. The in-app debug-only synthetic UI test now rebinds an empty timeline to another synthetic channel with multiple programmes and verifies the new channel's requested selection and click target. No production playback or recording is touched.
- **PASS — static consistency only:** six latest files fetched; all 24 `MainActivity` diagnostic event references resolve, all CI-asserted check identifiers remain present, all five touched Kotlin source files have balanced braces, and source recovery guard and synthetic recycler probe are wired.
- **UNVERIFIED / NOT RUN:** full Gradle and Android build at the latest SHA, emulator execution, physical TV D-pad/recycler behavior, and process-death restoration on real provider feeds. Repo sync and source consistency do not prove device acceptance.
- **Next highest priority:** inspect Android push-triggered Actions job logs; correct actual failures if any. Then add bounded instrumented recreation acceptance and persisted source recovery checks using injected synthetic inputs, without network credentials or new background services.

## 2026-11-11 final recovery-policy hardening

- Reject empty/whitespace-only saved source entries before attempting automatic restore, with matching JVM and autonomous in-app assertions.
- **PASS:** independently compiled the small Kotlin recovery policy with `kotlinc-jvm 1.9.0` and executed **5,210 assertions** after the blank-source change. The Android application and Kotlin unit test suite were not compiled or executed locally because an Android SDK/Gradle installation is unavailable in the execution environment.
- Latest confirmed direct-`main` commit at this checkpoint: `4e6c7b8`. GitHub connector shows no combined status or PR-triggered workflow runs for this SHA; this connector does not enumerate push-triggered Actions jobs. **CI build and emulator remain UNVERIFIED**, not failed or passed.

## 2026-11-11 checkpoint — strict resource-limit hardening

- **Baseline reconciled:** `main` at `6dc295f`, no open PRs/issues; inspected latest roadmap, manifest, downloader, playlist parser, recorder and Android diagnostics before edits. Selected two existing code paths rather than adding parallel services or replacement modules.
- **Root cause:** `SourceDownloader.skip` did not bound consecutive zero-byte bulk reads. `FilterInputStream.read(byte[], offset, length)` could also expose zero-return loops to XMLTV/M3U parsers. Fixed at the shared bulk-read entry point, with a limit of 16 consecutive zero reads, while preserving legal zero-length reads and decompressed-size enforcement. An additional nested resource-scoped cleanup closes the acquired source if header sniffing or gzip construction fails; the connection is disconnected in the outer catch.
- **Allocation reduction:** `PlaylistParser` now precomputes one source prefix and calls `MessageDigest.update` incrementally for source prefix, optional guide ID, zero separator and URL. The SHA-256 digest (first 12 bytes, lower-case hex) is byte-identical to the old concatenated string; existing IDs remain stable, with no data migration.
- **Files:** `core/.../SourceDownloader.kt`, `core/.../PlaylistParser.kt`, `core/.../CoreTest.kt`, `app/.../OnDeviceSelfTest.kt`, and `.github/workflows/android.yml`. Notable commits: `222bc7e`, `762f5ad`, `dbd8b64`, `97e5994`, `d856c3c`, `e8e2594`, `03f8c1a`, `6084f25`, `ee13a4d`.
- **PASS — executed locally:** Kotlin 1.9.0 compiled copies of the updated standalone downloader and playlist parser (with the exact bounded-line implementation and minimal model). Seven downloader cases passed (skip stall, direct bulk-read stall, temporary zero-read recovery, correct skip/byte counting, decompressed cap, source-sniff cleanup and corrupt-gzip cleanup); 301 compatibility assertions matched the pre-change SHA-256 algorithm. No Gradle/Android SDK was available for full app compilation, and these isolated tests are not Android device results. One false strict assertion about underlying `PushbackInputStream` read count was corrected to account for an initial read; tests reran PASS. The nested cleanup implementation removed a Kotlin compiler warning about a redundant mutable variable.
- **UNVERIFIED:** Android build on latest `main`, emulator's `offline-source-stall` and other assertions, real low-RAM device memory, recorded stream hardware failures. The GitHub connector's commit workflow query only returns PR-triggered runs and cannot establish push CI status; do not infer PASS or FAIL.
- **Next:** inspect actual GitHub Actions push-run build logs, fix any observed failures, then continue unattended lifecycle and playback acceptance with explicit heap/PSS budgets rather than introducing another background watchdog.

## 2026-11-11 checkpoint — low-allocation guide clock refresh

- Reconciled `main` at `40a77aa` against roadmap, open issues/PRs (none), and current `MainActivity`, `CableGuideAdapter`, `ProgramTimelineView`, diagnostics and emulator workflow. Found clock runnable called `renderChannels()` every 60 seconds, which notified the entire list adapter and rebuilt visible timeline entries despite unchanged search, guide and window.
- Commits directly to `main`: `d8f75cd` adds constant-memory `ProgramTimelineView.refreshNow`; `9e7b484` updates only attached guide row canvases; `ae77d39` switches activity timer to visible-row refresh; `a8167b9` suppresses timer when no guide or Activity stopped; `1b88ef2` adds a bounded debug-only Canvas pixel probe; `8fe5ca6` consolidates UI QA to one bounded event scan; `a7762d9` adds CI assertion `debug-now-marker`.
- No new background thread, service, polling interval, dependency, permanent bitmap or EPG cache. The synthetic debug probe allocates one 100×24 bitmap and recycles it. **PASS (static inspection only):** five modified code/workflow files fetched, all 14 CI assertions represented, all MainActivity self-test event references resolved, balanced braces in edited Kotlin, no `notifyDataSetChanged` in clock path, clock canceled on stop and guarded when no guide.
- **UNVERIFIED / NOT RUN:** Kotlin/Android Gradle compilation, emulator job result, actual device UI marker correctness, frame time, CPU, and RAM deltas. No Android SDK/Gradle available in this execution runtime; read-only GitHub Actions page/API attempts were inaccessible. Do not claim production or device performance certification. Next inspect exact push CI/emulator results, then verify sustained memory and GUIDE idle/scroll behavior on low-RAM hardware.
