# Feature status — TunerOS

**Status snapshot: 2026-11-10.** Source changes are not equivalent to a passing CI build or device acceptance.

| Capability | Status | Evidence / limitation |
|---|---|---|
| Android TV + phone single app | Implemented, UNVERIFIED on devices | Launcher and Leanback entries, placeholder icon/banner |
| M3U import | Implemented, CI pending latest | Bounded reader, truncation warnings, source-scoped IDs; new regressions |
| XMLTV import | Implemented, CI pending latest | XXE protection, bounded data, missing-timezone warning, truncation warnings |
| EPG lookup/search responsiveness | Implemented, CI pending latest | Per-channel sorted binary search; precomputed current-program map; virtualized rows; debounce |
| Hide channels without EPG | Implemented, CI pending latest | Checks for any schedule, including future programmes |
| Playlist/guide URL confidentiality | Implemented, device UNVERIFIED | Android Keystore AES-GCM; migrates legacy plaintext preferences |
| HTTP source downloader | Implemented, CI pending latest | HTTPS downgrade blocked, gzip sniffing, decompressed size limit; unit regressions |
| Media3 playback | Implemented, device UNVERIFIED | HLS/DASH/native media player; real streams untested |
| Stream Doctor | Planned | Basic error codes only |
| Guide Doctor | Planned | No mapping repair UI |
| Multiview selectable audio | NOT IMPLEMENTED | Only capacity policy and input validation; no tile/audio player |
| Classic Cable full timeline | NOT IMPLEMENTED | Alpha uses virtualized channel list |
| Favorites, profiles, database | NOT IMPLEMENTED | Planned |
| Android icon and TV banner | Implemented placeholders, device UNVERIFIED | XML vector resources, final branding pending |
| Gradle wrapper | NOT IMPLEMENTED | CI pins Gradle 8.9; local builds still require installed Gradle |
| FastChannels on-device server | DEFERRED | Explicitly out of current scope |
| Android APK | PASS for prior baseline | [Run 42737399473](https://github.com/DevTunaSoft/TunerOS/actions/runs/42737399473); latest changes pending verification |
| Real-device installation and playback | NOT RUN | Requires Android TV and mobile acceptance |
