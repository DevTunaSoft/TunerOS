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


## 2026-11-11 Stream Doctor first implementation (CI pending at authoring)

- New `core/StreamDoctor.kt` classifies Media3 numeric playback errors into short static diagnostic text (network, timeouts, provider access/limits, media format, device decoder, DRM and fallback). The official code reference is https://developer.android.com/reference/androidx/media3/common/PlaybackException.
- `MainActivity` now uses the same helper for live-channel and recording playback failure messages. It does **not** include exception messages, network URLs, user agents, referrers, tokens or provider headers.
- `StreamDoctorTest.kt` exercises classification and bounds/privacy properties. The classifier creates no background work and retains no error history; it only allocates the returned brief message when a player error occurs.
- **CI PENDING** for this new code; no device playback observed. Next: inspect the resulting main workflow and record PASS or FAIL without overstating real-device compatibility.
