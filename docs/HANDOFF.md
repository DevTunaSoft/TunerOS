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
