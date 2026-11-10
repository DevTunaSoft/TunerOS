# TunerOS roadmap

The master research backlog contains 134 prioritized items. Short-term release sequence:

1. **Foundation** — native application, source parsing, EPG, channel selection, basic playback, CI, verified APK.
2. **Guide** — full classic cable grid, channel search, user-controlled hide without EPG, favorites, stable channel mappings, Guide Doctor.
3. **Playback** — hardened switching, HLS/DASH/non-HLS validation, Stream Doctor, recovery, diagnostics.
4. **DVR/PVR (in progress)** — bounded direct-to-disk raw and MPEG-TS HLS capture, Record Now, exact-alarm future scheduling, recording library/playback, recovery and conflict handling. Protect/delete controls are now implemented in source. Next: storage destinations, series rules, timeshift, encrypted/fMP4 compatibility where safe, and real-device acceptance.
5. **Multiview** — two concurrent streams and audio selection first, then adaptive three/four tiles; never exceed decoder or provider concurrent-stream limits.
6. **Personalization** — guide themes, profiles, backups, parental controls, accessible TV focus.
7. **Later** — advanced source adapters and optional authorized channel catalog.

**Deferred:** FastChannels serving from the Android box. Preserve this feature as an architectural proposal, not a current acceptance target.

## Decision log

- 2026-11-08: Rename Android app to TunerOS.
- 2026-11-08: Keep original browser app separate. Treat old V8/V9 source as unidentified; do not overwrite an unrelated repository.
- 2026-11-08: Native Android player is Kotlin + Media3, shared testable JVM core, TV/mobile shell.
- 2026-11-08: Move two-to-four channel selectable-audio Multiview into early Android roadmap, after basic player stability.
- 2026-11-08: Defer the FastChannels on-device server until the core app is working.

## Stabilization checkpoint — 2026-11-10

Address source audit findings before adding new UI features. Changes include virtualized search, binary-search guide index, timezone/truncation warnings, secure source URL storage, redirect and gzip handling, and targeted regression tests. Validate CI and on-device behavior. Add a standard Gradle wrapper when the binary can be committed safely. Real Multiview audio selection remains future work, not a tested feature.


Windows bootstrap helper added as a temporary alternative to manually installing Gradle. A **standard Gradle wrapper** is still open; verify the helper on a Windows 11 machine and commit the official wrapper JAR/scripts after source verification.


## DVR implementation checkpoint — 2026-11-10

DVR is now active development rather than a later placeholder. The recorder is designed so recording duration does not grow its working buffer: raw streams use a fixed 64 KiB copy buffer, and HLS reuses one 64 KiB segment buffer while polling bounded manifests and retaining only new segment metadata. Future jobs are persisted separately from recording history; credential-bearing source URLs and provider headers are kept in Android Keystore-backed encrypted storage rather than plaintext SQLite. Exact-alarm permission is required before a background future recording is armed on modern Android. Encrypted HLS, fMP4 HLS, separate rendition muxing and DASH recording remain explicitly unsupported until implemented and tested.
