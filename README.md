# TunerOS

**Native Android TV and Android mobile IPTV player (early development)**

TunerOS aims to provide a classic-cable electronic program guide, reliable Media3 playback, Guide Doctor, Stream Doctor, and configurable multiview with one selected audio source. Early-alpha source now includes a focusable cable timeline and experimental direct-to-disk DVR/recording library. Initial builds use user-supplied M3U/XMLTV; FastChannels on-device serving is deferred.

## Status

**Early alpha / under construction.** Code and documentation are being brought into this repository. A debug APK passed GitHub Actions after the consolidated alpha guide/DVR merge on 2026-11-11 ([verified main build](https://github.com/DevTunaSoft/TunerOS/actions/runs/42972145588)). Security/performance stabilization changes passed unit tests and APK compilation. No physical-device acceptance or production readiness is claimed. See `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/AUDIT_FINDINGS_2026-11-10.md`, and `docs/HANDOFF.md` for precise status and engineering constraints.

## Build prerequisites

Android SDK (API 35), JDK 17, and Gradle 8.9. From project root, run `gradle :core:test :app:assembleDebug`. On Windows, optional `scripts/build.ps1` downloads Gradle 8.9 with a SHA-256 check (helper not yet device/Windows tested). APK output: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions is intended to run this on commits.

## License and upstream sources

Compatible open-source code may be selectively adapted after license and provenance review. The Classic Cable timeline model incorporates concepts adapted from Egeniq's Apache-2.0 `android-tv-program-guide`; attribution and modifications are recorded in `docs/THIRD_PARTY_PROVENANCE.md`. Users must have the rights to access their streams; bundling any catalog requires separately verified redistribution permissions.

Copyright (c) 2026 TunerOS contributors. Licensing decision is pending source and dependency review.
