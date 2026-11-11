# Feature status — TunerOS

**Status snapshot: 2026-11-11 (merged alpha on main; final range-validation changes CI pending).** Source changes are not equivalent to a passing CI build or device acceptance.

| Capability | Status | Evidence / limitation |
|---|---|---|
| Android TV + phone single app | Implemented, UNVERIFIED on devices | Launcher and Leanback entries, placeholder icon/banner |
| M3U import | Implemented, PASS (CI), device UNVERIFIED | Bounded reader, truncation warnings, source-scoped IDs; new regressions |
| XMLTV import | Implemented, PASS (CI), device UNVERIFIED | XXE protection, bounded data, missing-timezone warning, truncation warnings |
| XMLTV description retention | Implemented in PR, CI PENDING; device UNVERIFIED | Android import discards descriptions not displayed by the current alpha UI. Optional time-window retention remains disabled to preserve future-only schedules and the hide-no-EPG filter. No measured RAM improvement claimed. |
| EPG lookup/search responsiveness | Implemented, PASS (CI), device UNVERIFIED | Per-channel sorted binary search; precomputed current-program map; virtualized rows; debounce |
| Hide channels without EPG | Implemented, PASS (CI), device UNVERIFIED | Checks for any schedule, including future programmes |
| Playlist/guide URL confidentiality | Implemented, device UNVERIFIED | Android Keystore AES-GCM; migrates legacy plaintext preferences |
| HTTP source downloader | Implemented, PASS (CI), device UNVERIFIED | HTTPS downgrade blocked, gzip sniffing, decompressed size limit; unit regressions |
| Media3 playback | Implemented, device UNVERIFIED | HLS/DASH/native media player; real streams untested |
| Stream Doctor | Planned | Basic error codes only |
| Guide Doctor | Planned | No mapping repair UI |
| DVR/PVR recording foundation | IN PROGRESS, CI PENDING | Raw/progressive capture and unencrypted MPEG-TS HLS capture are wired to the foreground recorder with bounded direct-to-disk I/O. HLS supports master-variant selection, live-edge start, media-sequence deduplication, gap detection, byte ranges with length mismatch rejection, HTTPS downgrade protection, provider User-Agent/Referer headers, stalled-playlist detection, bounded manifests, and one reusable 64 KiB segment buffer per active recorder. Encrypted HLS, fMP4 HLS/EXT-X-MAP, master variants requiring separate external audio/video rendition capture, and DASH recording fail closed and remain unsupported. SQLite catalog, recording library/playback, interruption recovery, exact-alarm future scheduling/re-arm in stable 200-item batches, overlap rejection, schedule cancellation, duplicate-ID overwrite protection, and secure encrypted scheduled-source/header storage are implemented. Manual protect/unprotect and path-validated deletion are implemented with a v3 catalog flag; the database migration is device UNVERIFIED. User-selected local SAF storage (shared/SD/USB) is now implemented in source with persisted read/write grants, no broad storage permission, provider-reported free-space enforcement, direct streaming output, content-URI playback/deletion validation, no silent fallback when selected media is unavailable, and metadata preservation when a drive is disconnected. CI PASS: run 42848491493. Physical USB/SD acceptance remains UNVERIFIED. Series rules, timeshift, and real-device acceptance remain pending. |
| Multiview selectable audio | NOT IMPLEMENTED | Only capacity policy and input validation; no tile/audio player |
| Classic Cable full timeline | IN PROGRESS, CI PENDING | Pure-Kotlin timeline plus recycled Android TV rows wired for a three-hour window; fixed eight-cell pool per visible row, indexed window queries, focus details and select-to-play. Half-hour time rail, bounded left/right navigation, and a horizontally scrollable DVR action row now wired; current-time indicator and device focus acceptance still pending |
| Favorites, profiles, database | NOT IMPLEMENTED | Planned |
| Android icon and TV banner | Implemented placeholders, device UNVERIFIED | XML vector resources, final branding pending |
| Standard Gradle wrapper | NOT IMPLEMENTED | CI pins Gradle 8.9; optional Windows bootstrap script can download Gradle 8.9, but is not a wrapper |
| FastChannels on-device server | DEFERRED | Explicitly out of current scope |
| Android APK | PASS (CI) | [Run 42738309789](https://github.com/DevTunaSoft/TunerOS/actions/runs/42738309789) tests, assembleDebug, artifact upload |
| Real-device installation and playback | NOT RUN | Requires Android TV and mobile acceptance |
