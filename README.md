# TunerOS

**Native Android TV and Android mobile IPTV player (early development)**

TunerOS aims to provide a classic-cable electronic program guide, reliable Media3 playback, Guide Doctor, Stream Doctor, and configurable multiview with one selected audio source. Initial builds focus on user-supplied M3U/XMLTV. FastChannels on-device serving is deferred.

## Status

**Early alpha / under construction.** Code and documentation are being brought into this repository. A debug APK was built in GitHub Actions on 2026-11-10 ([verified stabilization build](https://github.com/DevTunaSoft/TunerOS/actions/runs/42738309789)). Security/performance stabilization changes passed unit tests and APK compilation. No physical-device acceptance or production readiness is claimed. See `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/AUDIT_FINDINGS_2026-11-10.md`, and `docs/HANDOFF.md` for precise status and engineering constraints.

## Build prerequisites

Android SDK (API 35), JDK 17, and Gradle 8.9. From project root, run `gradle :core:test :app:assembleDebug`. On Windows, optional `scripts/build.ps1` downloads Gradle 8.9 with a SHA-256 check (helper not yet device/Windows tested). APK output: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions is intended to run this on commits.

## License and upstream sources

No external IPTV project source is imported into this repository. Future adoption of upstream code requires an explicit license and provenance audit. Users must have the rights to access their streams; bundling any catalog requires separately verified redistribution permissions.

Copyright (c) 2026 TunerOS contributors. Licensing decision is pending source and dependency review.
