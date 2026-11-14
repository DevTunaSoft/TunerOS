# TunerOS — Master Product, History, and Development Handoff

**Prepared:** 2026-11-13 (US Central)  
**Repository:** https://github.com/DevTunaSoft/TunerOS  
**Primary branch:** `main` (per retrieved README)  
**Audience:** incoming lead engineer, Android developer, QA engineer, release engineer, Codex/agent team  
**Status:** living handoff; not a claim of production readiness.

## 1. Executive purpose and original vision
TunerOS is intended to be a polished, low-resource IPTV television experience for Android TV boxes and Android phones. It combines the familiar interaction model of a traditional cable television receiver with modern, user-supplied IPTV sources, a searchable electronic program guide (EPG), reliable playback, diagnostics, configurable multiview, and DVR/PVR. It should be approachable for ordinary viewers without exposing parser or network complexity. A TV remote should be a first-class input method, not an afterthought. The application must remain usable on constrained Android TV hardware with low RAM and modest CPUs.

The experience should feel like a set-top box: open the guide, move through channels and time, select a programme, and watch. Double-clicking or activating a guide channel should start playback where applicable. Users should be able to search channel names, hide channels lacking EPG data if they choose, and switch among visual skins; **Classic Cable is the intended default**. A multi-view mode inspired by sports-oriented television receivers should permit simultaneous streams with exactly one selected audio source. DVR should support recording now, scheduled recordings, a recording library, and ultimately series and timeshift functionality.

This is not a provider of pirated or bundled IPTV subscriptions. The baseline uses user-supplied M3U playlists and XMLTV guide sources. Redistribution and upstream-code licenses must be checked separately before shipping bundled content.

## 2. What prompted the redesign
Earlier browser-oriented IPTV work exposed practical weaknesses: provider feeds with thousands of channels and incomplete guide mappings; guide imports that failed or appeared empty; streams that were not browser-playable; HLS `manifestLoadError`; UI design that felt bland; and requirements for non-HLS streams and native DVR. Browser codec and transport restrictions cannot be solved reliably by cosmetic UI changes. The subsequent native Android design uses Media3 for device-native playback and platform-managed media capabilities. **Do not assume the original V8/V9 browser source was imported into this Android repository.** The repository's original handoff explicitly identifies the Android app as a new implementation, not a verified import of the old browser code.

### Product decisions and why
| Decision | Reason | Caveat |
|---|---|---|
| Android TV plus phone | Target TV-box and handheld use in one codebase | Remote and touch acceptance on real hardware still needed |
| Classic Cable default | Familiar guide-first navigation; user preferred cable aesthetic | Final branding and all selectable skins are not verified |
| User-controlled hide-no-EPG | Avoid clutter without permanently hiding channels users want | Must check existence of schedule, not only programme playing now |
| Search by channel name | Large playlists make scrolling alone impractical | Search must not trigger a full expensive guide scan per keystroke |
| Guide activation plays channel | Expected cable receiver behavior | Verify D-pad OK, double-click/touch and focus transitions |
| Native Media3 playback | Better Android transport/decoder support than browser-only playback | Real codec/provider compatibility remains unverified |
| Bounded direct-to-disk DVR | Recording without re-encoding reduces CPU/RAM | Codec/playlist subset, permissions, scheduling, storage failure modes matter |
| Selectable-audio multiview | Sports/cable-style viewing experience | Capacity policy exists; actual multi-player tiles not implemented |
| Local autonomous QA | Testing should not depend on user manually exercising the app | Synthetic emulator tests do not prove real-device compatibility |
| Low memory, clean modular code | Explicit non-negotiable design constraint | Performance claims require measurements, not code inspection alone |

## 3. User experience specification
**First launch:** present a clear TV-friendly setup path for user-provided M3U and XMLTV URLs; explain permission needs without exposing secrets. Preserve a previously validated source through restarts. An invalid replacement must not destroy a working configuration. Use a visible but non-sensitive warning when import is partial or the guide's timezone is assumed.

**Guide:** Classic Cable is the default layout. Channel rows must be recycled; the timeline must show programme blocks, a current-time indicator, clear focus, and predictable left/right time-window shifting. Empty EPG rows must remain navigable without pretending an empty cell can play a programme. Search should debounce typing. Hide-no-EPG is opt-in and must preserve channels with future-only schedule entries. A user can activate a channel to play, return to the guide, and retain appropriate state after configuration changes.

**Playback:** Media3 is responsible for native streaming. Channel switching should reuse a player only when header/context compatibility permits; avoid accumulating decoders and players. Report meaningful stream failures without leaking provider tokens. Handle screen-on behavior appropriately during active video and release resources when playback stops.

**DVR/PVR:** provide Record Now, schedule from guide, cancel scheduled recording, stop active recording, recent library, playback, protect/unprotect and safe deletion. Use encrypted scheduled-source details and a SQLite recording catalog. Recording must be raw/direct-to-disk where supported and must not load the whole stream into memory. Selected SAF storage should fail explicitly rather than silently switching locations. Interrupted and incomplete captures must be classified accurately.

**Multiview (target, not delivered):** configure a device-dependent maximum number of streams, render independent tiles, select exactly one audio source, allow focus changes via remote, and degrade gracefully when decoders or RAM are insufficient. Do not confuse a capacity-policy unit test with an implemented multiview interface.

**Diagnostics:** Guide Doctor should explain mismatches between playlist channel IDs and XMLTV IDs, time-zone assumptions, coverage gaps, and actionable repair options. Stream Doctor should distinguish playback errors and provide credential-safe guidance. Export reports explicitly on user request. The application must not silently transmit private source URLs or tokens.

## 4. Historical engineering timeline and reasoning
### Early browser/IPTV exploration
- Imported provider data exposed large playlist/EPG mismatches; a historical report cited **2,361 channels and 559 scheduled EPG IDs** from one source. This is a historical user report, not a current Android acceptance measurement.
- Requested a more functional visual redesign, Classic Cable as default, other skins, channel search, optional hide-no-EPG, guide activation to play, broader stream-format compatibility, and DVR/PVR.
- Evaluated outside projects for reusable concepts. External projects are references, not blanket authorization to copy unreviewed source.

### November 7–10: Android foundation and audit
- Established a pure Kotlin `core/` and Android `app/` split to make parser/policy testing independent of the Android runtime.
- Identified quadratic guide lookup behavior and UI stalls: repeated linear `now()` scans across programmes on each filter/search update. Replaced with sorted per-channel indexes and binary search; moved expensive work off the UI thread and debounced search.
- Addressed XMLTV timestamps without timezone offsets: UTC fallback plus warning rather than silently discarding programme entries. **Provider-specific timezone selection remains a requirement.**
- Changed channel/programme caps to return bounded partial results with warnings instead of discarding a complete feed. Added bounded M3U line reading.
- Protected stored source URLs with Android Keystore AES-GCM; hardened legacy plaintext migration against data loss. Moved cryptographic/disk work off the main thread.
- Hardened downloads: block HTTPS-to-HTTP redirect downgrades, sniff gzip bytes, cap decompressed output, count `skip()` toward read budgets, and avoid infinite no-progress loops.
- Added provider/source-aware stream identity to reduce duplicate `tvg-id` collisions.
- Recorded test-evidence corrections: seeded randomized smoke tests are not coverage-guided fuzzing.

### November 10–11: guide, DVR and testing architecture
- Introduced a TV-focused timeline and focus policy. D-pad boundaries must shift the time window; empty rows must not strand focus. The guide clock redraw should invalidate visible content rather than rebind entire lists.
- Implemented initial direct-to-disk DVR with a bounded 64 KiB segment-copy buffer, a single active recorder, foreground service, SQLite catalog, protected recording metadata, and encrypted scheduled-source details.
- Added exact-alarm scheduling with explicit blocked status when OS authorization is unavailable; a scheduled request is not proof an alarm fired.
- Implemented a local, bounded self-test/reporting facility and Android emulator CI gate. Tests must not alter the user's recordings, providers, or external media.

### November 12–14: fault containment and DVR lifecycle
- Expanded Android emulator synthetic tests to cover HLS manifest parsing, loopback HTTP capture, scratch-file writing, internal DVR sink finalization, collision protection, schedule reservation, recovery and lifecycle ordering.
- Added SAF removable-storage protections: capacity checks, persisted permissions, explicit failures, and no false claim of a completed file when rename/finalization fails.
- Hardened schedule state transitions: reserve a PREPARING row before storing secrets; only activate after encryption succeeds; recorder acknowledges STARTED; stale alarms cannot overwrite an active job; bounded startup cleanup reclaims abandoned PREPARING/RECOVERING rows.
- Prevented SQLite catalog closure while an active worker still needed it. Subsequent commits also changed cancellation and storage-reserve rechecks after network reads. **Latest commit search identified `d39ed825c756fc53b7aa1824ceb0854debd97a49` as the newest returned commit; its exact-commit CI result was not checked in this preparation.**

## 5. Repository and evidence baseline
Canonical repo: `DevTunaSoft/TunerOS`. The fetched `README.md` describes early alpha, JDK 17, Gradle 8.9, Android SDK 35 and `gradle :core:test :app:assembleDebug`. Its current file snapshot contains historical CI references including Android emulator run `43555173671` at `a62671c`, reporting **23 synthetic/local PASS, 0 FAIL, 1 BLOCKED, 12 NOT RUN**. This is evidence for that commit only, not a blanket green result for newer commits. The latest returned commit list begins with `d39ed825c756fc53b7aa1824ceb0854debd97a49` (DVR storage reserve handoff documentation). Repository search returned no open issues and no open PRs at query time; this is not a substitute for a complete branch/workflow audit.

Key repository docs to reconcile before modifying anything: `README.md`, `docs/HANDOFF.md`, `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/ON_DEVICE_QA.md`, `docs/AUDIT_FINDINGS_2026-11-10.md`, `docs/THIRD_PARTY_PROVENANCE.md`, and `.github/workflows/android.yml`.

## 6. Implemented vs incomplete vs unverified
| Area | Evidence-based handoff status |
|---|---|
| Pure Kotlin M3U/XMLTV parsers | Implemented; unit/emulator synthetic checks reported PASS; real feeds unverified |
| Indexed EPG search and filtering | Implemented; synthetic validation reported; physical-device scale/latency unverified |
| TV guide timeline and focus | In progress; synthetic focus tests exist; physical remote acceptance unverified |
| Android Media3 player | Implemented; real provider streams, codecs and hardware decoder behavior unverified |
| Encrypted source storage | Implemented; synthetic Keystore checks; migration/upgrade on devices needs evidence |
| Stream Doctor | Basic classifier implemented; full diagnostic telemetry not implemented |
| Guide Doctor | Planned; mapping-repair interface not implemented |
| DVR direct-to-disk capture | Implemented for selected raw/HLS subset; real provider recording unverified |
| Scheduled DVR and recovery | Implemented; synthetic checks reported; exact-alarm delivery/reboot acceptance pending |
| SAF USB/SD storage | Implemented in source; physical removal/reconnection acceptance pending |
| Multiview video/audio tiles | NOT IMPLEMENTED; only admission/capacity policy |
| Multiple completed UI skins | UNVERIFIED; Classic Cable remains target default |
| Autonomous in-app tests | Implemented; emulator PASS for cited commit; physical acceptance incomplete |
| Signed release/upgrade migration | NOT RUN/UNVERIFIED |
| Production low-RAM certification | NOT RUN |

## 7. Non-negotiable requirements
1. Prefer low allocations, fixed-size buffers, recycling, bounded caches and streaming data pipelines. Avoid polling, duplicate timers, unnecessary background services and simultaneous decoders.
2. Keep the core testable independently of Android. Put platform APIs behind small boundaries.
3. No secrets, provider URLs, headers or tokens in exported diagnostics or public CI logs.
4. No accidental data loss: preserve working sources on failed migration, preserve partial recordings honestly, and never claim success on failed storage finalization.
5. Avoid duplicate code, PRs, issues or branches; reconcile existing work first.
6. Do not overstate tests. Use PASS, FAIL, BLOCKED, NOT RUN and UNVERIFIED with exact commit/device/test context.
7. Testing should be autonomous where feasible, and untestable hardware conditions must remain blocked rather than assigned to end users as undocumented manual acceptance.
8. No bundled streams or third-party code without verified rights, attribution and redistribution permissions.

## 8. Incoming engineer's first 48 hours
**Step 1 — Baseline:** inspect default branch SHA, full tree, outstanding branches, Actions runs, issues, PRs and docs. Read the current `docs/HANDOFF.md` from its end, not only its beginning. Confirm latest changes to cancellation/storage reserve and worker shutdown. Identify drift between docs and code.

**Step 2 — Reproduce:** run `gradle --no-daemon :core:test :app:assembleDebug` with JDK 17/SDK 35/Gradle 8.9. Run Android 35 emulator QA exactly as CI does. Capture commit SHA, job URL, checks, failures and APK hash. No release claims based on a historical run.

**Step 3 — Prioritize:** resolve any failing CI or broken build first; then protect DVR cancellation/storage reserve/finalization and recovery; then device-level playback and remote navigation; then memory benchmarks and Guide Doctor; then multiview and advanced PVR.

**Step 4 — Test safely:** synthetic fixtures only in isolated scratch storage. Do not create or delete actual user recordings during QA. Test with a low-RAM emulator, a representative TV box and a phone when an authorized test rig exists. If physical devices are unavailable, record BLOCKED.

**Step 5 — Record:** add a dated evidence entry to the authoritative ledger including starting SHA, changed files, rationale, commands, results, test environment, final SHA, unresolved risk and next step. Update README, feature status and user manual only when relevant. Avoid conflicting source-of-truth status statements.

## 9. Known pitfalls and anti-regression guidance
- **Guide freeze:** never invoke O(programmes) per channel on each search keystroke; maintain indexes and cache current-program queries.
- **Guide semantics:** hide-no-EPG means no programme schedule, not simply nothing playing now; optional retention windows must not hide future-only channels.
- **XML parser:** Android SAX/JAXP feature support differs from desktop JVM. DTD/entity rejection must fail closed; depth/size limits matter. Verify on Android.
- **Source migration:** write encrypted value successfully before deleting plaintext fallback. No UI-thread Keystore or file operations.
- **HLS:** unsupported encrypted/fMP4/external-rendition/DASH recording must fail closed, not create misleading 'complete' files.
- **HTTP:** reject downgrade redirects, enforce byte caps after decompression, validate byte ranges and Content-Length, handle zero-progress reads, close all streams.
- **DVR cancellation:** recheck cancellation and free-space reserve after blocking reads and before writes; worker must finish finalization before catalog closure.
- **SAF:** disconnected media, unknown free space and failed rename are not successful recordings; preserve metadata and do not silently fallback.
- **Alarms:** authorization is not delivery; duplicate or stale callbacks must not clobber active jobs.
- **CI:** rerun after environment failure only with evidence; never mistake an SDK download outage for a code defect.

## 10. Release acceptance definition
Release is blocked until exact-commit build and emulator QA pass; real playback and first decoded frame are observed; TV remote navigation and touch controls work; recording/scheduling/reboot/USB failures are tested; 1 GiB and 2 GiB sustained memory/CPU/battery budgets are measured; source migration and upgrade are safe; licenses and branding are reviewed; signing and install/upgrade work; documentation matches shipping features. The acceptance matrix must distinguish tested scenarios from untestable environmental faults.

## 11. Suggested engineering ledger format
`Task ID | rationale | baseline SHA | files | implementation | tests | evidence URL | PASS/FAIL/BLOCKED/NOT RUN/UNVERIFIED | remaining risk | next owner/action`.

## 12. Immediate recovery prompt for Codex
> You are taking over DevTunaSoft/TunerOS. Read README.md, docs/HANDOFF.md, docs/FEATURE_STATUS.md, docs/ROADMAP.md, docs/ON_DEVICE_QA.md, docs/AUDIT_FINDINGS_2026-11-10.md, docs/THIRD_PARTY_PROVENANCE.md and the attached MASTER_HANDOFF.md and ENGINEERING_CODING_DOSSIER.md. Inspect actual main HEAD, recent commits, branches, PRs, issues and Actions before edits. Preserve clean modular Kotlin, bounded allocations, low RAM, source confidentiality, honest partial DVR semantics and autonomous evidence. Start with the newest DVR cancellation/storage reserve and worker-teardown changes, reproduce exact-commit tests, fix the highest-priority verified defect, add regression coverage, commit focused changes, and update the authoritative ledger. Do not claim real hardware acceptance without evidence or recreate already merged work. Continue through productive cycles without asking permission unless blocked by access or a consequential decision.

## 13. Source-of-truth caveat
This document combines user-established product requirements with a targeted live GitHub inspection. It does not reproduce or verify every historic branch, code line, CI run or earlier V8/V9 artifact. The live repository and actual test output always supersede narrative history. Never silently promote 'implemented' to 'verified'.


---
# Volume II — Expanded Product and Operational Continuity Manual

This expansion provides detailed rationale, acceptance, continuation and ownership for each major capability. It complements the test-case-level engineering dossier. Every acceptance paragraph is a requirement, not a claim of execution.

## Takeover principles and historical caveats

TunerOS originated in requests for a familiar, polished cable-style IPTV interface after earlier browser implementations showed guide mismatches, stream incompatibility and an unsatisfactory UI. The native Android repository is a distinct codebase, and prior V8/V9 source should not be assumed imported. The first priority is preserving working behavior and reducing crash, memory, corruption and recording risks. The second priority is completing core viewing and DVR hardware acceptance. Advanced multiview, Guide Doctor, timeshift, series recording, multiple skins and optional serving integrations remain roadmap work unless current code and runtime evidence prove otherwise. The project explicitly prioritizes clean modular code, very low memory consumption and fully autonomous test evidence.


## Operational takeover chapter 1: M3U playlist ingestion

**What the feature means to the viewer.** In TunerOS, m3u playlist ingestion is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is channel identities and playlist ordering. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made m3u playlist ingestion a reliability concern as TunerOS moved to Android. The adopted boundary is the bounded line reader and attribute extractor; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a playlist source with user-provided HTTP(S) credentials. The important failure cases are oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve the last validated playlist and source-specific stable identifiers. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the bounded line reader and attribute extractor, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record accepted channel count, warnings, source ID and elapsed monotonic duration during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a playlist source with user-provided HTTP(S) credentials. Verify channel identities and playlist ordering. Trigger representative failures from oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm the last validated playlist and source-specific stable identifiers after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 2: XMLTV schedule ingestion

**What the feature means to the viewer.** In TunerOS, xmltv schedule ingestion is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is correct channel association and programme windows. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made xmltv schedule ingestion a reliability concern as TunerOS moved to Android. The adopted boundary is the hardened streaming SAX reader; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes an XMLTV feed with mixed valid and invalid programme timestamps. The important failure cases are DTD payloads, 65-deep nesting, missing timezone and malformed end times. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve the previously validated guide and the ability to browse channels. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the hardened streaming SAX reader, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record accepted/ignored programme counts, warning categories and heap/PSS during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for an XMLTV feed with mixed valid and invalid programme timestamps. Verify correct channel association and programme windows. Trigger representative failures from DTD payloads, 65-deep nesting, missing timezone and malformed end times and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm the previously validated guide and the ability to browse channels after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 3: EPG indexing and lookup

**What the feature means to the viewer.** In TunerOS, epg indexing and lookup is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is current programme, window clipping and future-only visibility. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made epg indexing and lookup a reliability concern as TunerOS moved to Android. The adopted boundary is the per-channel binary-search and overlap prefix policy; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a sorted and unsorted mixture of overlapping channel programmes. The important failure cases are overlaps, zero-duration events, gaps and boundary seconds. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve guide consistency without rescanning every programme per keystroke. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the per-channel binary-search and overlap prefix policy, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record query duration, allocation delta and oracle equality during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a sorted and unsorted mixture of overlapping channel programmes. Verify current programme, window clipping and future-only visibility. Trigger representative failures from overlaps, zero-duration events, gaps and boundary seconds and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm guide consistency without rescanning every programme per keystroke after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 4: Classic Cable navigation

**What the feature means to the viewer.** In TunerOS, classic cable navigation is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is D-pad selection, time shifting and OK activation. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made classic cable navigation a reliability concern as TunerOS moved to Android. The adopted boundary is the timeline focus policy and Android view binding; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a TV guide with focusable programmes and empty schedule rows. The important failure cases are empty rows, edge crossing, rapid key repeat and activity recreation. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve the last valid focus target and an accessible channel activation route. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the timeline focus policy and Android view binding, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record focus ID, window offset, playback invocation count and dropped-frame count during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a TV guide with focusable programmes and empty schedule rows. Verify D-pad selection, time shifting and OK activation. Trigger representative failures from empty rows, edge crossing, rapid key repeat and activity recreation and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm the last valid focus target and an accessible channel activation route after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 5: Channel search and filters

**What the feature means to the viewer.** In TunerOS, channel search and filters is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is search matches and user-controlled visibility. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made channel search and filters a reliability concern as TunerOS moved to Android. The adopted boundary is the debounced search cache and hide-no-EPG predicate; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a large channel catalog with repeated names and missing guide IDs. The important failure cases are rapid edits, clearing search, future-only EPG and source reload. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve all user channels when the optional hide filter is disabled. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the debounced search cache and hide-no-EPG predicate, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record search latency, visible row count and predicate oracle result during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a large channel catalog with repeated names and missing guide IDs. Verify search matches and user-controlled visibility. Trigger representative failures from rapid edits, clearing search, future-only EPG and source reload and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm all user channels when the optional hide filter is disabled after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 6: Network source downloads

**What the feature means to the viewer.** In TunerOS, network source downloads is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is byte-limited, cancellable source retrieval. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made network source downloads a reliability concern as TunerOS moved to Android. The adopted boundary is the bounded downloader and redirect policy; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes an HTTP(S) endpoint with controlled redirects and compressed bodies. The important failure cases are HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve previous valid source configuration and no leaked token. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the bounded downloader and redirect policy, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record bytes consumed, redirect classification, timeout and safe error code during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for an HTTP(S) endpoint with controlled redirects and compressed bodies. Verify byte-limited, cancellable source retrieval. Trigger representative failures from HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm previous valid source configuration and no leaked token after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 7: Secure source persistence

**What the feature means to the viewer.** In TunerOS, secure source persistence is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is encrypted storage and startup reload. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made secure source persistence a reliability concern as TunerOS moved to Android. The adopted boundary is Android Keystore AES-GCM and migration logic; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a synthetic URL and headers containing canary credentials. The important failure cases are missing keys, corrupt ciphertext, failed migration and concurrent import. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve legacy source only when safe fallback is required and no destructive overwrite. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through Android Keystore AES-GCM and migration logic, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record key availability, ciphertext roundtrip and migration outcome without secrets during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a synthetic URL and headers containing canary credentials. Verify encrypted storage and startup reload. Trigger representative failures from missing keys, corrupt ciphertext, failed migration and concurrent import and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm legacy source only when safe fallback is required and no destructive overwrite after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 8: Live Media3 playback

**What the feature means to the viewer.** In TunerOS, live media3 playback is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is first frame, audio readiness, channel switching and resource release. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made live media3 playback a reliability concern as TunerOS moved to Android. The adopted boundary is the Media3 player controller; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a consented live stream or offline local playback fixture. The important failure cases are decoder exhaustion, unsupported MIME, bad headers and app backgrounding. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve responsive guide, stopped playback state and released decoders. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the Media3 player controller, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record first-frame time, buffering time, player instance count and PSS during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a consented live stream or offline local playback fixture. Verify first frame, audio readiness, channel switching and resource release. Trigger representative failures from decoder exhaustion, unsupported MIME, bad headers and app backgrounding and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm responsive guide, stopped playback state and released decoders after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 9: Stream Doctor

**What the feature means to the viewer.** In TunerOS, stream doctor is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is actionable non-sensitive error explanation. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made stream doctor a reliability concern as TunerOS moved to Android. The adopted boundary is the pure classifier and Android presentation layer; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes synthetic Media3 exception codes without private URL strings. The important failure cases are unknown codes, authentication failures, output failures and transport interruptions. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve a generic safe diagnostic when precise classification is impossible. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the pure classifier and Android presentation layer, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record classifier code, rendered message and leak scan during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for synthetic Media3 exception codes without private URL strings. Verify actionable non-sensitive error explanation. Trigger representative failures from unknown codes, authentication failures, output failures and transport interruptions and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm a generic safe diagnostic when precise classification is impossible after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 10: Raw progressive recording

**What the feature means to the viewer.** In TunerOS, raw progressive recording is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is exact byte copy, cancellation and finalization. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made raw progressive recording a reliability concern as TunerOS moved to Android. The adopted boundary is the fixed-buffer direct-to-disk recorder; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a bounded local HTTP byte stream and scratch output. The important failure cases are zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve a correctly classified partial file and uncorrupted catalog. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the fixed-buffer direct-to-disk recorder, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record byte count, digest, completion state and open-descriptor count during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a bounded local HTTP byte stream and scratch output. Verify exact byte copy, cancellation and finalization. Trigger representative failures from zero-byte reads, content-length mismatch, storage pressure and abrupt EOF and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm a correctly classified partial file and uncorrupted catalog after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 11: HLS recording

**What the feature means to the viewer.** In TunerOS, hls recording is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is sequence deduplication, byte ranges and ordered capture. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made hls recording a reliability concern as TunerOS moved to Android. The adopted boundary is the HLS parser, live-edge tracker and segment copier; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a synthetic unencrypted MPEG-TS playlist and localhost segments. The important failure cases are discontinuities, stale playlists, sequence gaps and changed variants. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve partial recording classification without duplicate segments. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the HLS parser, live-edge tracker and segment copier, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record requested segment IDs, copied bytes, offsets and completion status during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a synthetic unencrypted MPEG-TS playlist and localhost segments. Verify sequence deduplication, byte ranges and ordered capture. Trigger representative failures from discontinuities, stale playlists, sequence gaps and changed variants and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm partial recording classification without duplicate segments after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 12: DVR schedule lifecycle

**What the feature means to the viewer.** In TunerOS, dvr schedule lifecycle is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is correct PREPARING to armed to STARTED transitions. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made dvr schedule lifecycle a reliability concern as TunerOS moved to Android. The adopted boundary is the reservation state machine and exact-alarm receiver; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes multiple scheduled jobs with unique immutable identifiers. The important failure cases are stale callbacks, duplicate IDs, encryption failure and reboot rearm. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve no overwritten credentials, no duplicate recording and recoverable row. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the reservation state machine and exact-alarm receiver, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record schedule ID, expected state, alarm delivery and worker acknowledgement during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for multiple scheduled jobs with unique immutable identifiers. Verify correct PREPARING to armed to STARTED transitions. Trigger representative failures from stale callbacks, duplicate IDs, encryption failure and reboot rearm and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm no overwritten credentials, no duplicate recording and recoverable row after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 13: DVR foreground-service teardown

**What the feature means to the viewer.** In TunerOS, dvr foreground-service teardown is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is ordered stop, final write and database close. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made dvr foreground-service teardown a reliability concern as TunerOS moved to Android. The adopted boundary is the single worker and recording catalog lifecycle; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a recorder whose network read is delayed during shutdown. The important failure cases are onDestroy races, STOP during read and cancellation after capacity check. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve a safely finalized partial recording with no use-after-close. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the single worker and recording catalog lifecycle, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record worker completion time, final row state and database close ordering during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a recorder whose network read is delayed during shutdown. Verify ordered stop, final write and database close. Trigger representative failures from onDestroy races, STOP during read and cancellation after capacity check and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm a safely finalized partial recording with no use-after-close after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 14: SAF SD and USB recording

**What the feature means to the viewer.** In TunerOS, saf sd and usb recording is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is direct streaming with capacity reserve and safe rename. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made saf sd and usb recording a reliability concern as TunerOS moved to Android. The adopted boundary is the SAF sink and persisted permission validator; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a user-selected document-tree URI and controlled fake provider. The important failure cases are revoked grant, missing free-space data, drive removal and rename failure. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve preserved metadata, no silent internal-storage fallback. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the SAF sink and persisted permission validator, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record URI grant state, reported free bytes, rename outcome and file classification during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a user-selected document-tree URI and controlled fake provider. Verify direct streaming with capacity reserve and safe rename. Trigger representative failures from revoked grant, missing free-space data, drive removal and rename failure and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm preserved metadata, no silent internal-storage fallback after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 15: Recording catalog and library

**What the feature means to the viewer.** In TunerOS, recording catalog and library is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is consistent rows, protection flags and safe playback/deletion. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made recording catalog and library a reliability concern as TunerOS moved to Android. The adopted boundary is the catalog migrations and bounded recent queries; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a disposable SQLite catalog with completed and partial recordings. The important failure cases are database upgrades, collisions, missing files and malformed URI. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve protected recordings and unrelated documents. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the catalog migrations and bounded recent queries, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record row count, schema version, query bounds and deletion decision during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a disposable SQLite catalog with completed and partial recordings. Verify consistent rows, protection flags and safe playback/deletion. Trigger representative failures from database upgrades, collisions, missing files and malformed URI and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm protected recordings and unrelated documents after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 16: Autonomous diagnostic runner

**What the feature means to the viewer.** In TunerOS, autonomous diagnostic runner is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is objective PASS/FAIL/BLOCKED/NOT RUN evidence. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made autonomous diagnostic runner a reliability concern as TunerOS moved to Android. The adopted boundary is the app-local self-test engine and private report writer; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes synthetic canaries isolated from user media and credentials. The important failure cases are interrupted report, log cap, failed fixture and unavailable hardware. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve unmodified user sources, playback and recordings. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the app-local self-test engine and private report writer, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record test ID, timestamps, environment, assertion counts and PSS during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for synthetic canaries isolated from user media and credentials. Verify objective PASS/FAIL/BLOCKED/NOT RUN evidence. Trigger representative failures from interrupted report, log cap, failed fixture and unavailable hardware and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm unmodified user sources, playback and recordings after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 17: Lifecycle and process recovery

**What the feature means to the viewer.** In TunerOS, lifecycle and process recovery is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is restoration without stale overwrite or oversized Bundles. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made lifecycle and process recovery a reliability concern as TunerOS moved to Android. The adopted boundary is the startup reload worker and saved-state policy; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes an Activity restart or process kill with a previously validated source. The important failure cases are rotation, rapid manual replacement, background stop and corrupted state. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve validated encrypted source and stable UI responsiveness. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.
**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the startup reload worker and saved-state policy, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record reload count, source selection, query length and player release during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for an Activity restart or process kill with a previously validated source. Verify restoration without stale overwrite or oversized Bundles. Trigger representative failures from rotation, rapid manual replacement, background stop and corrupted state and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm validated encrypted source and stable UI responsiveness after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 18: Low-memory performance

**What the feature means to the viewer.** In TunerOS, low-memory performance is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is stable heap, PSS and CPU without UI stalls. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made low-memory performance a reliability concern as TunerOS moved to Android. The adopted boundary is the parser, indexes, recycled views and bounded recorder; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a 1 GiB or 2 GiB target profile with large synthetic guide. The important failure cases are repeated imports, scrolling, long recording and decoder churn. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve an operable guide and a correctly stopped recorder under pressure. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the parser, indexes, recycled views and bounded recorder, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record peak PSS, GC count, jank, CPU time and battery delta during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a 1 GiB or 2 GiB target profile with large synthetic guide. Verify stable heap, PSS and CPU without UI stalls. Trigger representative failures from repeated imports, scrolling, long recording and decoder churn and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm an operable guide and a correctly stopped recorder under pressure after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 19: CI and release packaging

**What the feature means to the viewer.** In TunerOS, ci and release packaging is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is reproducible artifact, independent checks and traceable evidence. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made ci and release packaging a reliability concern as TunerOS moved to Android. The adopted boundary is the Gradle build and Android emulator workflow; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a clean checkout at an exact immutable commit. The important failure cases are missing SDK, flaky downloads, stale artifact and docs-only changes. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve no false green build and no published unverified release. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the Gradle build and Android emulator workflow, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record commit SHA, job status, APK digest and report contents during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a clean checkout at an exact immutable commit. Verify reproducible artifact, independent checks and traceable evidence. Trigger representative failures from missing SDK, flaky downloads, stale artifact and docs-only changes and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm no false green build and no published unverified release after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.


## Operational takeover chapter 20: Branding and accessible UX

**What the feature means to the viewer.** In TunerOS, branding and accessible ux is not an isolated engineering exercise: it supports the central promise of a responsive television appliance. Its functional success condition is legible guide, clear focus, usable navigation and complete branding. The user should see predictable behavior and a meaningful, non-sensitive message when the capability is unavailable. The viewer should not have to perform development diagnostics or manually inspect files.

**Why engineering changed course.** The original browser-based experience encountered limitations that made branding and accessible ux a reliability concern as TunerOS moved to Android. The adopted boundary is the launcher resources, themes and accessible controls; this separates deterministic policy from Android-specific behavior wherever feasible. The expected input includes a TV remote and phone with the selected TunerOS branding kit. The important failure cases are small screen, overscan, large font, missing art and contrast failure. These hazards explain why simply adding UI features without bounded parsing, storage and lifecycle controls would undermine the project.

**Design intent versus evidence.** The project must preserve working Classic Cable default without cropped essential controls. Source presence does not mean the behavior has passed real Android TV acceptance. The successor must verify the current implementation against the repository and label results as PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED. When documentation cites an older CI run, it proves only the specific commit and assertions from that run.

**What the next engineer must inspect.** Trace the relevant call chain from user action or startup through the launcher resources, themes and accessible controls, including cancellation, error mapping and cleanup. Examine whether expensive work runs on the main thread; whether any buffers, indexes or temporary lists scale with feed size; whether repeated operations retain old objects; whether exceptions are redacted; and whether Android lifecycle transitions leave an inconsistent state. Record focus traversal, layout bounds, text contrast and asset provenance during bounded autonomous tests.

**Acceptance definition.** Build an isolated fixture for a TV remote and phone with the selected TunerOS branding kit. Verify legible guide, clear focus, usable navigation and complete branding. Trigger representative failures from small screen, overscan, large font, missing art and contrast failure and require a controlled refusal, partial state or recoverable error rather than data loss. Confirm working Classic Cable default without cropped essential controls after cancellation and restart. Measure CPU, RAM and I/O on the declared test device, and save a reproducible report. If the scenario cannot be safely automated, record the missing rig or platform permission as a blocker instead of assigning manual work to the viewer.

**Documentation and handoff obligation.** After any change, update the task ledger with the original problem, technical rationale, alternatives considered, affected paths, tests, exact commit, evidence, remaining limitations and rollback. Explain user-visible behavior in the manual. Keep any deferred capability visibly deferred; do not turn an intended roadmap item into an implemented feature by changing only documentation.

## Practical engineering handoff: a new developer's first week

**Day 1: establish facts.** Clone the canonical repository, capture `git rev-parse HEAD`, inspect branches, PRs and issues, read the README and all current docs, inspect CI run status and check whether the repository has a standard Gradle wrapper. Do not change source until the baseline is recorded. Compare this dossier's source snapshot with the current tree and explicitly note anything newer.

**Day 2: reproduce the baseline.** With JDK 17, Android SDK 35 and Gradle 8.9 (unless updated in the repository), execute `gradle --no-daemon :core:test :app:assembleDebug`. Preserve command output and APK checksum. Confirm emulator job and private self-test artifact on the same SHA. If infrastructure downloads fail, separate environmental failure from application failure and avoid altering product code to accommodate a transient service outage.

**Day 3: verify high-risk seams.** Inspect the schedule reservation/Keystore transaction, alarm receiver and service acknowledgement, HLS byte-count integrity, cancellation after network reads, storage reserve checks, and worker-to-catalog teardown. These are historically sensitive to data loss, duplicate writes and false completion. Add deterministic regression tests only where coverage is genuinely missing.

**Day 4: instrument hardware and resources.** Develop or use authorized automated device harnesses for TV remote navigation, first video frame, audio, channel switching, low-RAM guides, background/foreground transitions, exact alarms and removable storage. Physical USB disconnection, provider outages and platform-granted permissions require external test rigs or observation and must not be faked as full acceptance.

**Day 5: reconcile and publish.** Update the feature matrix with exact evidence, assign next unblocked workstreams without shared-file conflicts, document limitations and release blockers, and publish a handoff with exact recovery instructions. Keep docs synchronized; no generic 'all tests passed' statement without listing what was actually executed.

## Final continuation rule

The next developer should never restart the project from scratch, reimplement existing features because a chat summary omitted them, or repeatedly request permission to continue ordinary in-scope development. Work from the actual repository state, preserve data and tests, and continue with the highest-priority unblocked defect or acceptance gate. If access or execution stops, leave a precise ledger entry identifying the last verified SHA, next command and unresolved blocker.