# Feature status — TunerOS

**Status snapshot: 2026-11-10.** Source changes are not equivalent to a passing CI build or device acceptance.

| Capability | Status | Evidence / limitation |
|---|---|---|
| Android TV + phone single app | Implemented, UNVERIFIED on devices | Launcher and Leanback entries, placeholder icon/banner |
| M3U import | Implemented, PASS (CI), device UNVERIFIED | Bounded reader, truncation warnings, source-scoped IDs; new regressions |
| XMLTV import | Implemented, PASS (CI), device UNVERIFIED | XXE protection, bounded data, missing-timezone warning, truncation warnings |
| EPG lookup/search responsiveness | Implemented, PASS (CI), device UNVERIFIED | Per-channel sorted binary search; precomputed current-program map; virtualized rows; debounce |
| Hide channels without EPG | Implemented, PASS (CI), device UNVERIFIED | Checks for any schedule, including future programmes |
| Playlist/guide URL confidentiality | Implemented, device UNVERIFIED | Android Keystore AES-GCM; migrates legacy plaintext preferences |
| HTTP source downloader | Implemented, PASS (CI), device UNVERIFIED | HTTPS downgrade blocked, gzip sniffing, decompressed size limit; unit regressions |
| Media3 playback | Implemented, device UNVERIFIED | HLS/DASH/native media player; real streams untested |
| Stream Doctor | Planned | Basic error codes only |
| Guide Doctor | Planned | No mapping repair UI |
| Multiview selectable audio | NOT IMPLEMENTED | Only capacity policy and input validation; no tile/audio player |
| Classic Cable full timeline | IN PROGRESS, CI PENDING | Pure-Kotlin contiguous timeline/gap model added with Apache-2.0 Egeniq provenance; Android TV grid/focus UI not yet wired |
| Favorites, profiles, database | NOT IMPLEMENTED | Planned |
| Android icon and TV banner | Implemented placeholders, device UNVERIFIED | XML vector resources, final branding pending |
| Standard Gradle wrapper | NOT IMPLEMENTED | CI pins Gradle 8.9; optional Windows bootstrap script can download Gradle 8.9, but is not a wrapper |
| FastChannels on-device server | DEFERRED | Explicitly out of current scope |
| Android APK | PASS (CI) | [Run 42738309789](https://github.com/DevTunaSoft/TunerOS/actions/runs/42738309789) tests, assembleDebug, artifact upload |
| Real-device installation and playback | NOT RUN | Requires Android TV and mobile acceptance |
