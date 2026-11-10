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
