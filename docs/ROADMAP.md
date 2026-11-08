# TunerOS roadmap

The master research backlog contains 134 prioritized items. Short-term release sequence:

1. **Foundation** — native application, source parsing, EPG, channel selection, basic playback, CI, verified APK.
2. **Guide** — full classic cable grid, channel search, user-controlled hide without EPG, favorites, stable channel mappings, Guide Doctor.
3. **Playback** — hardened switching, HLS/DASH/non-HLS validation, Stream Doctor, recovery, diagnostics.
4. **Multiview** — two concurrent streams and audio selection first, then adaptive three/four tiles; never exceed decoder or provider concurrent-stream limits.
5. **Personalization** — guide themes, profiles, backups, parental controls, accessible TV focus.
6. **Later** — DVR, recording, advanced source adapters and optional authorized channel catalog.

**Deferred:** FastChannels serving from the Android box. Preserve this feature as an architectural proposal, not a current acceptance target.

## Decision log

- 2026-11-08: Rename Android app to TunerOS.
- 2026-11-08: Keep original browser app separate. Treat old V8/V9 source as unidentified; do not overwrite an unrelated repository.
- 2026-11-08: Native Android player is Kotlin + Media3, shared testable JVM core, TV/mobile shell.
- 2026-11-08: Move two-to-four channel selectable-audio Multiview into early Android roadmap, after basic player stability.
- 2026-11-08: Defer the FastChannels on-device server until the core app is working.
