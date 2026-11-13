# TunerOS

**Native Android TV and Android mobile IPTV player (early development)**

TunerOS aims to provide a classic-cable electronic program guide, reliable Media3 playback, Guide Doctor, Stream Doctor, and configurable multiview with one selected audio source. Early-alpha source now includes a focusable cable timeline and experimental direct-to-disk DVR/recording library. Initial builds use user-supplied M3U/XMLTV; FastChannels on-device serving is deferred.

## Status

**Early alpha / under construction.** Code and documentation are being brought into this repository. A debug APK passed GitHub Actions after the consolidated alpha guide/DVR merge on 2026-11-11 ([verified main build](https://github.com/DevTunaSoft/TunerOS/actions/runs/42976022983)). Security/performance stabilization changes passed unit tests and APK compilation. No physical-device acceptance or production readiness is claimed. See `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/AUDIT_FINDINGS_2026-11-10.md`, and `docs/HANDOFF.md` for precise status and engineering constraints.

## Autonomous on-device diagnostics

TunerOS now runs an unattended, bounded set of local self-tests on startup and after source imports. The TV/phone interface shows a PASS/FAIL/BLOCKED/NOT RUN summary, and the horizontally scrolling actions row provides a `Self-test report` with an explicitly shared export option. The first checks cover synthetic M3U/XMLTV and DTD rejection, encrypted source storage, scratch DVR copy, exact-alarm access and optional recording destination access. Real provider events are logged locally with numeric codes, uptime timestamps and process PSS snapshots—never source URLs or provider tokens. **This code has not yet been accepted on Android hardware.** See [on-device QA evidence and remaining gates](docs/ON_DEVICE_QA.md) and the [user manual](docs/USER_MANUAL.md).

**Autonomous Android CI gate:** GitHub Actions now includes an Android 35 (2 GiB) emulator job. After the APK build, CI launches TunerOS and checks the app's own private synthetic QA report for parser, guide, SQLite catalog, recording-copy and Keystore assertions. No manual testing steps are needed. The emulator gate **PASS** was verified in [main run 43354840233](https://github.com/DevTunaSoft/TunerOS/actions/runs/43354840233) on `7094302`: 16 synthetic/local PASS, 0 FAIL, 1 BLOCKED exact-alarm permission, 12 NOT RUN real-provider/hardware scenarios. Physical Android TV, real streams, SD/USB, and sustained RAM tests are not certified.

## Build prerequisites

Android SDK (API 35), JDK 17, and Gradle 8.9. From project root, run `gradle :core:test :app:assembleDebug`. On Windows, optional `scripts/build.ps1` downloads Gradle 8.9 with a SHA-256 check (helper not yet device/Windows tested). APK output: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions is intended to run this on commits.

## License and upstream sources

Compatible open-source code may be selectively adapted after license and provenance review. The Classic Cable timeline model incorporates concepts adapted from Egeniq's Apache-2.0 `android-tv-program-guide`; attribution and modifications are recorded in `docs/THIRD_PARTY_PROVENANCE.md`. Users must have the rights to access their streams; bundling any catalog requires separately verified redistribution permissions.

Copyright (c) 2026 TunerOS contributors. Licensing decision is pending source and dependency review.

## Latest verified Android QA — November 13, 2026

[GitHub Actions 43472251052](https://github.com/DevTunaSoft/TunerOS/actions/runs/43472251052) **PASS** at `834a4cd`: Kotlin tests, APK build/artifact and Android 35 emulator QA. The app reported **17 synthetic/local PASS, 0 FAIL, 1 BLOCKED, 12 NOT RUN**, including HLS live-edge de-duplication. A low-allocation HLS capture change avoids per-poll filtered lists; debug-only QA verifies it with two synthetic output bytes. Real TV-box low-memory behavior, provider streams, hardware decoder and recording/USB/reboot acceptance are still open. See [the detailed QA ledger](docs/ON_DEVICE_QA.md).
