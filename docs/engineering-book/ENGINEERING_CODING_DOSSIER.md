# TunerOS — Engineering and Coding Dossier

**Companion to:** `MASTER_HANDOFF.md`  
**Repository:** https://github.com/DevTunaSoft/TunerOS  
**Purpose:** implementation contracts, architectural rationale, failure modes, verification and takeover instructions.

## A. Engineering mission
Ship a native Android TV/mobile IPTV application that resembles a polished classic cable receiver while preserving low RAM/CPU/storage use. Prefer stable, understandable Kotlin code over fashionable frameworks, speculative abstractions or duplicate subsystems. Playback, guide import and DVR must remain safe under malformed feeds, weak networks, interrupted operations and constrained Android devices. Evidence must be reproducible, bounded and credential-safe.

## B. Source hierarchy and audit procedure
Read the current Git tree and code before relying on any narrative document. Verify `main` HEAD, branch protection, open PRs, issues, latest Actions, workflow concurrency, and artifacts. Read `README.md`, `docs/HANDOFF.md`, `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/ON_DEVICE_QA.md`, `docs/AUDIT_FINDINGS_2026-11-10.md`, `docs/THIRD_PARTY_PROVENANCE.md`. The retrieved README described JDK 17, Gradle 8.9, SDK 35 and `:core:test :app:assembleDebug`; do not assume these remain current after new commits. Latest commit search returned `d39ed825c756fc53b7aa1824ceb0854debd97a49` and preceding cancellation/storage fixes; verify branch HEAD and exact CI before claiming they passed.

## C. Module boundaries
### `core/`: pure Kotlin
Owns bounded playlist/guide parsing, normalized data models, programme indexing, timeline geometry/focus policies, playback error classification where Android-free, multiview capacity policy, network stream helpers where abstractable, and raw/HLS recording algorithms. Android-independent code should be tested with fast JVM unit tests, deterministic random regression and isolated fault fixtures. No `Activity`, `Context`, view, `ContentResolver` or Android Keystore dependency in pure core.

### `app/`: Android platform adapter
Owns launcher/TV intent handling, Activity lifecycle, recycled guide rows and timeline rendering, Media3 player, encrypted source storage, SQLite recording catalog and schedule state, foreground DVR service, exact-alarm receivers, SAF document access, Android diagnostics, and debug-only synthetic QA. UI and I/O must remain separate: blocking downloads, encryption, SQLite queries and guide parsing run on a bounded worker, not the main thread.

### `.github/workflows/android.yml`
Build/test gate with an Android 35 emulator running the app's own synthetic checks. Distinguish infrastructure failures from application failures. Retain artifacts long enough for troubleshooting, but do not export provider URLs or private media. Prevent recursive or redundant workflows and document the precise commit that passed.

## D. Architecture and data-flow map
```
User M3U URL -> bounded downloader -> bounded M3U parser -> source-scoped channel IDs
                                                     |-> channel list/guide rows
User XMLTV URL -> bounded downloader -> hardened XMLTV parser -> per-channel sorted index
                                                     |-> time-window queries
                                                     |-> current programme lookup
Channel activation -> playback controller -> Media3 -> device decoder/display
Recording request -> secure source snapshot -> schedule/catalog reservation
     -> exact alarm or Record Now -> foreground service -> raw/HLS copy
     -> bounded storage sink -> finalization -> SQLite library -> playback/delete
Debug launch -> isolated synthetic fixtures -> in-app assertions -> bounded private report
```

## E. Data model invariants
- Every channel identity must be stable within a source and distinguish streams even when `tvg-id` repeats. Avoid using the guide ID alone as a database primary key.
- Preserve source origin and avoid leaking URLs/headers in logs.
- Programme start/end values use a consistent epoch/timezone convention; reject invalid intervals and preserve overlap semantics.
- A guide filter for 'has EPG' must include future-only schedules, not just the current programme.
- Bound input size, line length, attributes, strings, nesting depth, programme count, and resulting memory retention.
- Distinguish missing data, malformed data, unsupported format, partial import and a successful empty schedule.
- Recording rows have immutable IDs; status transitions must not permit stale callbacks to overwrite an active recording.
- Only finalized files are 'complete'; partial files must remain distinguishable and deletable/recoverable.

## F. M3U parsing contracts
Use streaming bounded line reads rather than `readLine()` on untrusted arbitrarily long input. Parse `#EXTINF` metadata selectively; avoid materializing an attribute map when only a few keys are consumed. Bound channel count and string lengths, return explicit truncation warnings, and preserve useful partial results. Pool repeated header values within a parse only when memory/CPU measurements justify it. Incremental SHA-256 identities should include source and stream context. Test CRLF/LF, malformed quotes, duplicate IDs, enormous lines, Unicode, blank URLs, header variants, truncated feeds and repeated imports. Never retain whole source text when a streaming parser suffices.

## G. XMLTV parsing contracts
Use an Android-supported streaming XML reader and mandatory rejection of DTD/external entities; reject rather than silently proceeding if secure configuration fails. Enforce a nesting ceiling (repository status describes 64 levels) and bounded title/name/description lengths. UTC fallback for timezone-less timestamps is a deliberate compromise: emit warning, expose provider-zone configuration in future, and test offset/DST transitions. Index programmes by channel, sort in place, and use binary search for window queries. Where overlaps exist, retain the prefix-max-end structure needed for correctness; avoid allocating it for provably non-overlapping schedules. Optional retention windows must not change hide-no-EPG semantics. Add fixtures for malformed timestamps, XML bombs, duplicate programmes, overlapping intervals, DST, absent timezone, sparse/future-only channels and cap boundaries.

## H. EPG UI and remote-navigation contracts
Guide must be navigable with D-pad Up/Down/Left/Right/OK and Back, plus touch on phones. Recycle rows and avoid creating a view for every programme. The current-time marker should redraw visible canvas content only; no full dataset rebind every minute. Debounce search and cache filter results. Keep empty rows focusable and shift the visible time window on left/right boundaries; never launch playback on an empty cell. Ensure focus restoration after switching playback/guide, configuration changes and Activity recreation. Verify on real TV hardware or an authorized automated key-injection rig. Synthetic view focusability does not prove remote UX.

## I. Media3 playback contracts
Prefer one active player and release it when leaving playback; only reuse when provider headers and media context are compatible. Observe player states, first decoded video frame, audio readiness, buffering, errors and one-shot startup timeout without adding a permanent polling loop. A transition to `isPlaying` is not proof of a decoded frame. Keep screen on only during appropriate active playback. Avoid exposing provider headers/URLs through exception text. Test rapid channel switching, repeated stop/start, rotation, background/foreground, unsupported codecs, network loss, redirects and decoder exhaustion. Browser HLS `manifestLoadError` from the historical project is not proof of the same Android failure; diagnose on the target platform.

## J. DVR capture and HLS subset
Initial design copies encoded stream bytes to storage without transcoding, using one reusable ~64 KiB segment buffer per recorder. Support progressive/raw and a deliberately restricted unencrypted MPEG-TS HLS subset. The retrieved feature status describes media-sequence tracking, live-edge de-duplication, bounded manifest parsing, byte-range/Content-Range checks, stalled-playlist detection, HTTPS downgrade refusal and provider User-Agent/Referer handling. Encrypted HLS, fMP4/`EXT-X-MAP`, separate audio/video renditions and DASH recording must fail explicitly until supported. Do not report partial capture as successful complete media. Validate declared content lengths and reject short/long bodies. Check cancellation and free-space reserve after blocking reads, before writes and during finalization. Never allocate one buffer per segment or accumulate a whole live manifest history.

## K. DVR state machine
Recommended invariant model (verify exact enums in source):
```
NEW -> PREPARING -> ARMED -> START_REQUESTED -> STARTED -> COMPLETED
                   |             |             |-> PARTIAL / FAILED / CANCELLED
                   |-> FAILED    |-> BLOCKED
PREPARING / RECOVERING stale -> bounded recovery -> terminal/rebookable
```
Only the recorder acknowledges STARTED; an alarm delivery or service-start request alone does not. Reserve a stable schedule ID in SQLite before persisting encrypted credentials. Activate only when encrypted details are committed. Never overwrite a started job with stale alarm failure. Overlap and duplicate-ID checks must be atomic. On boot/restart, recover bounded pages, verify matching secret keys before cleanup, and re-arm eligible jobs in bounded batches. A job without exact-alarm permission must be visibly BLOCKED/unarmed, not optimistically marked scheduled-for-delivery. Device reboot and actual alarm delivery are still unverified from the reviewed documents.

## L. Recording storage and lifecycle
Support app-private storage and user-selected SAF destinations with persisted read/write grants. Do not request broad storage permissions when scoped SAF suffices. Treat unknown free-space information as unsafe when the chosen provider requires a reserve. Keep recording metadata when a removable drive disconnects. Never silently write elsewhere if the selected destination disappears. Validate URI ownership and file path before playback or deletion. Write to a partial/pending target, finalize/rename only on success, and verify finalization. Protect user-marked recordings from accidental deletion. Ensure worker shutdown is serialized: worker stops and finalizes catalog writes before SQLite closes. Avoid races between STOP, `onDestroy`, network read completion, file rename and catalog close.

## M. Security and privacy threat model
**Assets:** source URLs (possibly embedding tokens), optional request headers, scheduled job secrets, recordings, metadata, logs and signing credentials. **Threats:** malicious playlist/XMLTV input, XXE, decompression bombs, oversized lines, unsafe redirects, URL/token leakage, tampered SAF provider behavior, SQL/state corruption, untrusted third-party code and accidental credential exposure in CI. **Controls:** Keystore AES-GCM, no plaintext secret logging, safe migration, bounded streaming parser, DTD denial, HTTPS downgrade rejection, fixed-size buffers, URI/path validation, isolated scratch QA, explicit export, no hidden telemetry. **Residual risks:** user-configured HTTP transports are not encrypted in transit; provider URL credentials exist in memory while needed for playback; Android Keystore migration behavior must be tested on actual upgrades; no assertion of DRM support.

## N. Resource efficiency strategy
- Prefer O(log n) programme lookups to O(n) scans on every row/keystroke.
- Keep UI rows recycled; no whole-guide view tree.
- Avoid repeated list/filter allocations for guide windows and HLS polling.
- Stream to disk with one fixed buffer, no full recording in heap.
- Use one bounded import worker and one active recorder unless explicit capacity testing justifies more.
- Suspend clock callbacks while guide is hidden; no unnecessary wake locks or background polling.
- Bound diagnostics (reviewed QA docs describe one latest report and a 32 KiB event file).
- Measure process PSS, Java heap, native heap, CPU, frame time, disk I/O, battery and thermal behavior on representative 1 GiB/2 GiB devices. A single emulator PSS snapshot around 55–62 MiB from historical runs is not a sustained production memory ceiling.

## O. Autonomous testing system
Production startup self-tests should be lightweight and non-disruptive. Heavy synthetic stress, focus, local HTTP fixtures and storage fault tests belong behind an explicit debug/CI gate. Run them in the app with scratch data and no user media mutation. Record wall/monotonic time, device/build/commit, assertion ID, outcome and bounded numeric metrics; exclude secrets and raw exception messages. The reviewed QA ledger cites Android 35 emulator synthetic counts of **23 PASS / 0 FAIL / 1 BLOCKED / 12 NOT RUN** at commit `a62671c` in run `43555173671`. Do not extrapolate those numbers to newer commits or real devices. Include test failures in CI artifacts, with bounded logcat only when necessary.

## P. Test matrix and acceptance criteria
| Layer | Must verify | Evidence |
|---|---|---|
| JVM unit | M3U, XMLTV, timeline, guide indexes, error classifier, DVR core | deterministic assertions at exact SHA |
| Property/random | overlap oracle, duplicate IDs, caps, malformed inputs | seeds and number of cases |
| Security | XXE, zip bombs, downgrade, `skip()` bypass, secret-free reports | failing inputs and expected rejection |
| Android emulator | parser, Keystore, SQLite, scratch recording, focus, HLS loopback | app-generated report + CI URL |
| DVR concurrency | STOP during blocking read, duplicate alarm, worker shutdown, catalog close | deterministic latches/fault injection |
| DVR storage | capacity loss, unknown free space, rename failure, USB disconnect | controlled fault injection or BLOCKED |
| Android TV | real remote focus, first decoded video frame, decoder limits | device model, OS, evidence |
| Phone | touch, rotation, lifecycle, playback, accessibility | device model, OS, evidence |
| Long run | sustained 1/2 GiB PSS, CPU, battery, recording | time-series metrics |
| Release | signing, fresh install, upgrade/migration, rollback/recovery | package hashes and artifacts |

## Q. Priority engineering backlog
**P0 — latest-code verification:** inspect main HEAD, verify newest DVR storage-reserve/cancellation/teardown changes and exact-commit CI. Fix any regressions and align documentation.

**P0 — DVR correctness:** exercise cancellation after blocking reads, unknown-capacity SAF, short HTTP bodies, partial rename, catalog closure, duplicate alarms, reboot re-arm and credential cleanup. All must have bounded deterministic regression tests.

**P1 — physical-device acceptance:** automate TV D-pad and first-frame observations, real stream compatibility and removable media on authorized hardware. If no hardware exists, document BLOCKED rather than requesting informal user testing.

**P1 — performance:** benchmark 25k-channel/250k-programme feeds, scrolling/search latency, sustained 1/2 GiB PSS and DVR I/O. Keep regression budgets realistic and report raw metrics.

**P1 — Guide Doctor:** implement provider-scoped mapping diagnostics, channel-to-EPG match counts, timezone warnings, missing coverage and exportable credential-safe summaries.

**P2 — multiview:** establish actual decoder admission, per-tile Media3 lifecycle, one-audio-source switching, remote focus and memory-driven degradation. Begin with two tiles, then scale only with measured device capacity.

**P2 — PVR completion:** series rules, conflict resolution, timeshift, retention/protection policy, scheduler UX, library polish and export/recovery.

**P2 — polish/release:** final brand assets, skins, accessibility, signed builds, migration acceptance, legal/provenance review and user manual.

## R. Why specific earlier fixes must not be reverted
1. **Indexed guide**: removes the root cause of large-guide search/UI stalls.
2. **Timezone warnings**: prevents silently missing guide data and makes assumptions inspectable.
3. **Partial imports**: salvages valid records while clearly indicating truncated feeds.
4. **Bounded reads and decompression**: prevents malicious or malformed feeds from exhausting RAM.
5. **Keystore migration safeguards**: avoids irreversible loss of previously working source configurations.
6. **Provider-scoped IDs**: prevents duplicate guide identifiers from collapsing different channels.
7. **HTTPS downgrade refusal**: avoids silent transport-security regression.
8. **Single recorder/buffer**: constrains resource use on low-end boxes.
9. **PREPARING reservation**: avoids duplicate schedules and overwriting secrets.
10. **Recorder-owned STARTED transition**: distinguishes intent to start from actual work.
11. **Worker-before-catalog teardown**: prevents writes after SQLite closure.
12. **Post-read cancellation/reserve checks**: prevents writing after STOP or storage-capacity change during network stalls.
13. **Fail-closed SAF finalization**: prevents phantom completed recordings.
14. **Synthetic CI isolation**: ensures tests cannot destroy customer media.

## S. Implementation quality checklist
Before merge: prove no duplicated feature; define failure invariants; avoid unnecessary dependencies; use structured errors; add positive, negative, boundary and interruption tests; check Android lifecycle; ensure streams/cursors/files close in `finally`/`use`; test no-progress and partial reads; avoid credentials in diagnostics; compare allocations and PSS where relevant; document exact SHA and evidence; update authoritative ledger; inspect CI results; preserve protected branches and release signing. After two identical failures, stop retrying and change investigative approach.

## T. Reproducible build and recovery
Clone the repository, inspect toolchain files, install JDK 17/Android SDK 35/Gradle 8.9 as currently documented, run `gradle --no-daemon :core:test :app:assembleDebug`, and check `app/build/outputs/apk/debug/app-debug.apk`. Use GitHub Actions for emulator acceptance. A standard Gradle wrapper was previously noted as missing; verify whether it has since been added. Do not treat a successful APK build as successful video playback. On failures, preserve build logs, Gradle version, Android SDK version, failing test, source SHA and any environmental outage evidence. Never publish credential-bearing inputs.

## U. Code-review attack scenarios
- Gigabyte-scale XMLTV or gzip expansion, XML entities, 65+ nesting, truncated tags.
- M3U with a single multi-megabyte line, duplicate IDs, empty URLs, malicious headers.
- Guide with all future programmes, overlapping programme intervals, DST changes, huge sparse schedules.
- HLS playlist repeats media sequence, skips sequence, declares wrong Content-Length, returns zero reads, redirects to HTTP, requires unsupported encryption.
- User hits STOP while network read blocks; recorder receives bytes after cancellation; storage becomes full between reserve checks.
- SAF drive vanishes during rename; provider reports unknown free space; content URI changes or permission is revoked.
- Two alarm deliveries race; old alarm callback follows STARTED; process dies during PREPARING; Keystore write fails after row reservation.
- Activity rotates mid-import; user edits source while auto-restore completes; player switching races lifecycle shutdown.
- CI emulator SDK download fails; avoid misdiagnosing infrastructure as code and avoid unbounded retries.

## V. Documentation and release provenance
Track all adapted source by upstream URL, exact revision, license, notice and modification in `docs/THIRD_PARTY_PROVENANCE.md`. The reviewed provenance file records concepts adapted from Egeniq's Apache-2.0 `android-tv-program-guide`, not wholesale copying. Audit dependencies and redistribution rights before release. Keep README concise, feature status factual, roadmap forward-looking, manual user-facing, handoff chronological, and QA ledger evidence-oriented. This dossier explains design rationale but is not a substitute for exact source comments and tests.

## W. Handoff completion definition
A successor can take over without asking what the app is, why major architectural choices were made, which features exist, what remains unfinished, how to build and test, which bugs must not recur, which sources are sensitive, and what to do next. The only acceptable unanswered questions are those requiring fresh repository/device evidence or an explicit product/legal decision. Never fabricate that evidence.

## X. Takeover checklist
- [ ] Inspect `main` HEAD, branch list, open PRs/issues, Actions, and latest docs.
- [ ] Reconcile latest DVR commits with existing code and tests.
- [ ] Run exact-commit JVM build and emulator gate.
- [ ] Capture hashes, commands, result counts, failure classifications and artifacts.
- [ ] Audit security/resource invariants and prioritize an unblocked fix.
- [ ] Add deterministic regression and fault-injection coverage.
- [ ] Commit focused change without duplicate branches/PRs.
- [ ] Update ledger, handoff, roadmap, feature status and manual where changed.
- [ ] State physical-device and real-provider acceptance honestly.
- [ ] Continue to next unblocked high-priority task.

## Y. Verification caveat
This is a source-informed engineering specification and historical decision record, **not** a complete line-by-line source audit. A targeted GitHub inspection retrieved repository documentation and recent commit metadata; it did not independently execute the Android build or inspect every source file. Exact-commit CI and hardware testing remain necessary before declaring a feature production-ready.


---

# Extended Engineering Evidence and Takeover Reference — Volume II

**Authority model:** This section is a design and verification specification, not a claim that its proposed scenarios have been run. Unless a specific immutable GitHub Actions run and exact code SHA are named, each scenario below is **NOT RUN**. This prevents a future engineer from mistaking detailed instructions for verified outcomes. The existing repository documents remain the historical record; the incoming engineer must reconcile them with the current source.

## Evidence classification and source confidence

A historical user requirement describes intended behavior. A source-code observation demonstrates an implementation choice but not necessarily correct execution. A JVM test demonstrates only the specific fixture/assertions. An Android emulator result establishes that an Android runtime exercised those checks at one commit. A physical Android TV acceptance result would additionally establish remote, decoder, vendor firmware, memory, power and storage behavior for that tested model. These evidence tiers must never be collapsed into one PASS status. The retrieved README cites a successful Android emulator run at `a62671c` with 23 synthetic/local PASS, one exact-alarm BLOCKED and 12 real-world scenarios NOT RUN. More recent commits were observed in history, but their exact post-change CI status was not established by this package.

## Product promise and non-goals, in operational terms

TunerOS should feel like a television appliance, not a development tool. The primary path is: provide an authorized playlist, optionally provide an XMLTV guide, browse a familiar channel grid, activate a channel, watch, and optionally record permitted content. A viewer should not need to understand M3U syntax, XMLTV timestamps, decoder formats, Android alarms, SAF tree permissions or HTTP redirects. Those details belong in diagnostics and actionable error messages. The guide must remain useful when metadata is missing; incomplete EPG coverage is not grounds to hide channels without explicit viewer choice. Native Android playback is a pragmatic response to browser codec and CORS/transport limits, not a promise that all providers or DRM schemes will work. The app does not supply subscriptions, bypass DRM, distribute third-party streams, guarantee cloud DVR, or run a FastChannels server in the current release scope.

## Hard architectural constraints for a successor

The core parser and policy layer remains Android-free so its algorithms can be verified quickly and independently. Android owns Keystore, Media3, SQLite, Activity/service lifecycle, SAF and alarms. UI callbacks must never parse a complete guide or decrypt a source synchronously. The recorder must use fixed buffers and a single active writer unless a measured, verified design change explicitly supports more. A failed import cannot replace a previously validated source; a failed recording cannot become COMPLETE. Synthetic QA cannot modify a user's catalog, provider credentials, recording directory or actual media. A failure report cannot contain URLs, authentication headers, programme titles, or raw exception messages that might disclose secrets. Resource optimizations are accepted only when equivalence tests preserve user-visible semantics.


## 1. M3U playlist ingestion: component-level engineering contract

**Purpose and ownership.** This domain exists to make channel identities and playlist ordering reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the bounded line reader and attribute extractor. Its representative input is a playlist source with user-provided HTTP(S) credentials. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is channel identities and playlist ordering. Known hazards include oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. The system must preserve the last validated playlist and source-specific stable identifiers. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect accepted channel count, warnings, source ID and elapsed monotonic duration. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 01.01 — M3U playlist ingestion: happy path

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.02 — M3U playlist ingestion: minimum boundary

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.03 — M3U playlist ingestion: maximum supported size

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.04 — M3U playlist ingestion: malformed input

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.05 — M3U playlist ingestion: interrupted operation

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.06 — M3U playlist ingestion: concurrent event

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.07 — M3U playlist ingestion: repeated operation

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.08 — M3U playlist ingestion: restart recovery

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.09 — M3U playlist ingestion: permission or capability loss

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.10 — M3U playlist ingestion: slow or stalled I/O

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.11 — M3U playlist ingestion: data integrity

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.12 — M3U playlist ingestion: privacy and telemetry

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.13 — M3U playlist ingestion: resource exhaustion

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.14 — M3U playlist ingestion: upgrade compatibility

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 01.15 — M3U playlist ingestion: idempotency

**Setup and stimulus:** Start from a playlist source with user-provided HTTP(S) credentials; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the bounded line reader and attribute extractor and include at least one representative case involving oversized EXTINF records, malformed attributes, duplicated tvg-id and partial cap exhaustion. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert channel identities and playlist ordering, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last validated playlist and source-specific stable identifiers. Inspect accepted channel count, warnings, source ID and elapsed monotonic duration; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 2. XMLTV schedule ingestion: component-level engineering contract

**Purpose and ownership.** This domain exists to make correct channel association and programme windows reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the hardened streaming SAX reader. Its representative input is an XMLTV feed with mixed valid and invalid programme timestamps. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is correct channel association and programme windows. Known hazards include DTD payloads, 65-deep nesting, missing timezone and malformed end times. The system must preserve the previously validated guide and the ability to browse channels. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect accepted/ignored programme counts, warning categories and heap/PSS. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 02.01 — XMLTV schedule ingestion: happy path

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.02 — XMLTV schedule ingestion: minimum boundary

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.03 — XMLTV schedule ingestion: maximum supported size

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.04 — XMLTV schedule ingestion: malformed input

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.05 — XMLTV schedule ingestion: interrupted operation

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.06 — XMLTV schedule ingestion: concurrent event

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.07 — XMLTV schedule ingestion: repeated operation

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.08 — XMLTV schedule ingestion: restart recovery

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.09 — XMLTV schedule ingestion: permission or capability loss

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.10 — XMLTV schedule ingestion: slow or stalled I/O

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.11 — XMLTV schedule ingestion: data integrity

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.12 — XMLTV schedule ingestion: privacy and telemetry

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.13 — XMLTV schedule ingestion: resource exhaustion

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.14 — XMLTV schedule ingestion: upgrade compatibility

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 02.15 — XMLTV schedule ingestion: idempotency

**Setup and stimulus:** Start from an XMLTV feed with mixed valid and invalid programme timestamps; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the hardened streaming SAX reader and include at least one representative case involving DTD payloads, 65-deep nesting, missing timezone and malformed end times. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct channel association and programme windows, with an explicit failure category if the input or environment is unsupported. Verify preservation of the previously validated guide and the ability to browse channels. Inspect accepted/ignored programme counts, warning categories and heap/PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 3. EPG indexing and lookup: component-level engineering contract

**Purpose and ownership.** This domain exists to make current programme, window clipping and future-only visibility reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the per-channel binary-search and overlap prefix policy. Its representative input is a sorted and unsorted mixture of overlapping channel programmes. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is current programme, window clipping and future-only visibility. Known hazards include overlaps, zero-duration events, gaps and boundary seconds. The system must preserve guide consistency without rescanning every programme per keystroke. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect query duration, allocation delta and oracle equality. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 03.01 — EPG indexing and lookup: happy path

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.02 — EPG indexing and lookup: minimum boundary

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.03 — EPG indexing and lookup: maximum supported size

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.04 — EPG indexing and lookup: malformed input

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.05 — EPG indexing and lookup: interrupted operation

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.06 — EPG indexing and lookup: concurrent event

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.07 — EPG indexing and lookup: repeated operation

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.08 — EPG indexing and lookup: restart recovery

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.09 — EPG indexing and lookup: permission or capability loss

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.10 — EPG indexing and lookup: slow or stalled I/O

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.11 — EPG indexing and lookup: data integrity

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.12 — EPG indexing and lookup: privacy and telemetry

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.13 — EPG indexing and lookup: resource exhaustion

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.14 — EPG indexing and lookup: upgrade compatibility

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 03.15 — EPG indexing and lookup: idempotency

**Setup and stimulus:** Start from a sorted and unsorted mixture of overlapping channel programmes; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the per-channel binary-search and overlap prefix policy and include at least one representative case involving overlaps, zero-duration events, gaps and boundary seconds. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert current programme, window clipping and future-only visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of guide consistency without rescanning every programme per keystroke. Inspect query duration, allocation delta and oracle equality; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 4. Classic Cable navigation: component-level engineering contract

**Purpose and ownership.** This domain exists to make D-pad selection, time shifting and OK activation reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the timeline focus policy and Android view binding. Its representative input is a TV guide with focusable programmes and empty schedule rows. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is D-pad selection, time shifting and OK activation. Known hazards include empty rows, edge crossing, rapid key repeat and activity recreation. The system must preserve the last valid focus target and an accessible channel activation route. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect focus ID, window offset, playback invocation count and dropped-frame count. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 04.01 — Classic Cable navigation: happy path

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.02 — Classic Cable navigation: minimum boundary

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.03 — Classic Cable navigation: maximum supported size

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.04 — Classic Cable navigation: malformed input

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.05 — Classic Cable navigation: interrupted operation

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.06 — Classic Cable navigation: concurrent event

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.07 — Classic Cable navigation: repeated operation

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.08 — Classic Cable navigation: restart recovery

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.09 — Classic Cable navigation: permission or capability loss

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.10 — Classic Cable navigation: slow or stalled I/O

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.11 — Classic Cable navigation: data integrity

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.12 — Classic Cable navigation: privacy and telemetry

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.13 — Classic Cable navigation: resource exhaustion

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.14 — Classic Cable navigation: upgrade compatibility

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 04.15 — Classic Cable navigation: idempotency

**Setup and stimulus:** Start from a TV guide with focusable programmes and empty schedule rows; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the timeline focus policy and Android view binding and include at least one representative case involving empty rows, edge crossing, rapid key repeat and activity recreation. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert D-pad selection, time shifting and OK activation, with an explicit failure category if the input or environment is unsupported. Verify preservation of the last valid focus target and an accessible channel activation route. Inspect focus ID, window offset, playback invocation count and dropped-frame count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 5. Channel search and filters: component-level engineering contract

**Purpose and ownership.** This domain exists to make search matches and user-controlled visibility reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the debounced search cache and hide-no-EPG predicate. Its representative input is a large channel catalog with repeated names and missing guide IDs. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is search matches and user-controlled visibility. Known hazards include rapid edits, clearing search, future-only EPG and source reload. The system must preserve all user channels when the optional hide filter is disabled. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect search latency, visible row count and predicate oracle result. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 05.01 — Channel search and filters: happy path

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.02 — Channel search and filters: minimum boundary

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.03 — Channel search and filters: maximum supported size

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.04 — Channel search and filters: malformed input

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.05 — Channel search and filters: interrupted operation

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.06 — Channel search and filters: concurrent event

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.07 — Channel search and filters: repeated operation

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.08 — Channel search and filters: restart recovery

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.09 — Channel search and filters: permission or capability loss

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.10 — Channel search and filters: slow or stalled I/O

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.11 — Channel search and filters: data integrity

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.12 — Channel search and filters: privacy and telemetry

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.13 — Channel search and filters: resource exhaustion

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.14 — Channel search and filters: upgrade compatibility

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 05.15 — Channel search and filters: idempotency

**Setup and stimulus:** Start from a large channel catalog with repeated names and missing guide IDs; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the debounced search cache and hide-no-EPG predicate and include at least one representative case involving rapid edits, clearing search, future-only EPG and source reload. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert search matches and user-controlled visibility, with an explicit failure category if the input or environment is unsupported. Verify preservation of all user channels when the optional hide filter is disabled. Inspect search latency, visible row count and predicate oracle result; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 6. Network source downloads: component-level engineering contract

**Purpose and ownership.** This domain exists to make byte-limited, cancellable source retrieval reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the bounded downloader and redirect policy. Its representative input is an HTTP(S) endpoint with controlled redirects and compressed bodies. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is byte-limited, cancellable source retrieval. Known hazards include HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. The system must preserve previous valid source configuration and no leaked token. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect bytes consumed, redirect classification, timeout and safe error code. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 06.01 — Network source downloads: happy path

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.02 — Network source downloads: minimum boundary

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.03 — Network source downloads: maximum supported size

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.04 — Network source downloads: malformed input

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.05 — Network source downloads: interrupted operation

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.06 — Network source downloads: concurrent event

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.07 — Network source downloads: repeated operation

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.08 — Network source downloads: restart recovery

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.09 — Network source downloads: permission or capability loss

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.10 — Network source downloads: slow or stalled I/O

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.11 — Network source downloads: data integrity

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.12 — Network source downloads: privacy and telemetry

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.13 — Network source downloads: resource exhaustion

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.14 — Network source downloads: upgrade compatibility

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 06.15 — Network source downloads: idempotency

**Setup and stimulus:** Start from an HTTP(S) endpoint with controlled redirects and compressed bodies; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the bounded downloader and redirect policy and include at least one representative case involving HTTPS downgrade, gzip magic, infinite zero reads and truncated bodies. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert byte-limited, cancellable source retrieval, with an explicit failure category if the input or environment is unsupported. Verify preservation of previous valid source configuration and no leaked token. Inspect bytes consumed, redirect classification, timeout and safe error code; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 7. Secure source persistence: component-level engineering contract

**Purpose and ownership.** This domain exists to make encrypted storage and startup reload reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is Android Keystore AES-GCM and migration logic. Its representative input is a synthetic URL and headers containing canary credentials. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is encrypted storage and startup reload. Known hazards include missing keys, corrupt ciphertext, failed migration and concurrent import. The system must preserve legacy source only when safe fallback is required and no destructive overwrite. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect key availability, ciphertext roundtrip and migration outcome without secrets. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 07.01 — Secure source persistence: happy path

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.02 — Secure source persistence: minimum boundary

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.03 — Secure source persistence: maximum supported size

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.04 — Secure source persistence: malformed input

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.05 — Secure source persistence: interrupted operation

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.06 — Secure source persistence: concurrent event

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.07 — Secure source persistence: repeated operation

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.08 — Secure source persistence: restart recovery

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.09 — Secure source persistence: permission or capability loss

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.10 — Secure source persistence: slow or stalled I/O

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.11 — Secure source persistence: data integrity

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.12 — Secure source persistence: privacy and telemetry

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.13 — Secure source persistence: resource exhaustion

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.14 — Secure source persistence: upgrade compatibility

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 07.15 — Secure source persistence: idempotency

**Setup and stimulus:** Start from a synthetic URL and headers containing canary credentials; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise Android Keystore AES-GCM and migration logic and include at least one representative case involving missing keys, corrupt ciphertext, failed migration and concurrent import. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert encrypted storage and startup reload, with an explicit failure category if the input or environment is unsupported. Verify preservation of legacy source only when safe fallback is required and no destructive overwrite. Inspect key availability, ciphertext roundtrip and migration outcome without secrets; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 8. Live Media3 playback: component-level engineering contract

**Purpose and ownership.** This domain exists to make first frame, audio readiness, channel switching and resource release reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the Media3 player controller. Its representative input is a consented live stream or offline local playback fixture. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is first frame, audio readiness, channel switching and resource release. Known hazards include decoder exhaustion, unsupported MIME, bad headers and app backgrounding. The system must preserve responsive guide, stopped playback state and released decoders. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect first-frame time, buffering time, player instance count and PSS. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 08.01 — Live Media3 playback: happy path

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.02 — Live Media3 playback: minimum boundary

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.03 — Live Media3 playback: maximum supported size

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.04 — Live Media3 playback: malformed input

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.05 — Live Media3 playback: interrupted operation

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.06 — Live Media3 playback: concurrent event

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.07 — Live Media3 playback: repeated operation

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.08 — Live Media3 playback: restart recovery

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.09 — Live Media3 playback: permission or capability loss

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.10 — Live Media3 playback: slow or stalled I/O

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.11 — Live Media3 playback: data integrity

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.12 — Live Media3 playback: privacy and telemetry

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.13 — Live Media3 playback: resource exhaustion

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.14 — Live Media3 playback: upgrade compatibility

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 08.15 — Live Media3 playback: idempotency

**Setup and stimulus:** Start from a consented live stream or offline local playback fixture; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the Media3 player controller and include at least one representative case involving decoder exhaustion, unsupported MIME, bad headers and app backgrounding. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert first frame, audio readiness, channel switching and resource release, with an explicit failure category if the input or environment is unsupported. Verify preservation of responsive guide, stopped playback state and released decoders. Inspect first-frame time, buffering time, player instance count and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 9. Stream Doctor: component-level engineering contract

**Purpose and ownership.** This domain exists to make actionable non-sensitive error explanation reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the pure classifier and Android presentation layer. Its representative input is synthetic Media3 exception codes without private URL strings. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is actionable non-sensitive error explanation. Known hazards include unknown codes, authentication failures, output failures and transport interruptions. The system must preserve a generic safe diagnostic when precise classification is impossible. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect classifier code, rendered message and leak scan. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 09.01 — Stream Doctor: happy path

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.02 — Stream Doctor: minimum boundary

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.03 — Stream Doctor: maximum supported size

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.04 — Stream Doctor: malformed input

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.05 — Stream Doctor: interrupted operation

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.06 — Stream Doctor: concurrent event

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.07 — Stream Doctor: repeated operation

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.08 — Stream Doctor: restart recovery

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.09 — Stream Doctor: permission or capability loss

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.10 — Stream Doctor: slow or stalled I/O

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.11 — Stream Doctor: data integrity

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.12 — Stream Doctor: privacy and telemetry

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.13 — Stream Doctor: resource exhaustion

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.14 — Stream Doctor: upgrade compatibility

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 09.15 — Stream Doctor: idempotency

**Setup and stimulus:** Start from synthetic Media3 exception codes without private URL strings; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the pure classifier and Android presentation layer and include at least one representative case involving unknown codes, authentication failures, output failures and transport interruptions. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert actionable non-sensitive error explanation, with an explicit failure category if the input or environment is unsupported. Verify preservation of a generic safe diagnostic when precise classification is impossible. Inspect classifier code, rendered message and leak scan; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 10. Raw progressive recording: component-level engineering contract

**Purpose and ownership.** This domain exists to make exact byte copy, cancellation and finalization reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the fixed-buffer direct-to-disk recorder. Its representative input is a bounded local HTTP byte stream and scratch output. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is exact byte copy, cancellation and finalization. Known hazards include zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. The system must preserve a correctly classified partial file and uncorrupted catalog. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect byte count, digest, completion state and open-descriptor count. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 10.01 — Raw progressive recording: happy path

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.02 — Raw progressive recording: minimum boundary

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.03 — Raw progressive recording: maximum supported size

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.04 — Raw progressive recording: malformed input

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.05 — Raw progressive recording: interrupted operation

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.06 — Raw progressive recording: concurrent event

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.07 — Raw progressive recording: repeated operation

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.08 — Raw progressive recording: restart recovery

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.09 — Raw progressive recording: permission or capability loss

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.10 — Raw progressive recording: slow or stalled I/O

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.11 — Raw progressive recording: data integrity

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.12 — Raw progressive recording: privacy and telemetry

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.13 — Raw progressive recording: resource exhaustion

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.14 — Raw progressive recording: upgrade compatibility

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 10.15 — Raw progressive recording: idempotency

**Setup and stimulus:** Start from a bounded local HTTP byte stream and scratch output; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the fixed-buffer direct-to-disk recorder and include at least one representative case involving zero-byte reads, content-length mismatch, storage pressure and abrupt EOF. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert exact byte copy, cancellation and finalization, with an explicit failure category if the input or environment is unsupported. Verify preservation of a correctly classified partial file and uncorrupted catalog. Inspect byte count, digest, completion state and open-descriptor count; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 11. HLS recording: component-level engineering contract

**Purpose and ownership.** This domain exists to make sequence deduplication, byte ranges and ordered capture reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the HLS parser, live-edge tracker and segment copier. Its representative input is a synthetic unencrypted MPEG-TS playlist and localhost segments. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is sequence deduplication, byte ranges and ordered capture. Known hazards include discontinuities, stale playlists, sequence gaps and changed variants. The system must preserve partial recording classification without duplicate segments. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect requested segment IDs, copied bytes, offsets and completion status. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 11.01 — HLS recording: happy path

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.02 — HLS recording: minimum boundary

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.03 — HLS recording: maximum supported size

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.04 — HLS recording: malformed input

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.05 — HLS recording: interrupted operation

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.06 — HLS recording: concurrent event

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.07 — HLS recording: repeated operation

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.08 — HLS recording: restart recovery

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.09 — HLS recording: permission or capability loss

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.10 — HLS recording: slow or stalled I/O

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.11 — HLS recording: data integrity

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.12 — HLS recording: privacy and telemetry

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.13 — HLS recording: resource exhaustion

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.14 — HLS recording: upgrade compatibility

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 11.15 — HLS recording: idempotency

**Setup and stimulus:** Start from a synthetic unencrypted MPEG-TS playlist and localhost segments; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the HLS parser, live-edge tracker and segment copier and include at least one representative case involving discontinuities, stale playlists, sequence gaps and changed variants. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert sequence deduplication, byte ranges and ordered capture, with an explicit failure category if the input or environment is unsupported. Verify preservation of partial recording classification without duplicate segments. Inspect requested segment IDs, copied bytes, offsets and completion status; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 12. DVR schedule lifecycle: component-level engineering contract

**Purpose and ownership.** This domain exists to make correct PREPARING to armed to STARTED transitions reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the reservation state machine and exact-alarm receiver. Its representative input is multiple scheduled jobs with unique immutable identifiers. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is correct PREPARING to armed to STARTED transitions. Known hazards include stale callbacks, duplicate IDs, encryption failure and reboot rearm. The system must preserve no overwritten credentials, no duplicate recording and recoverable row. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect schedule ID, expected state, alarm delivery and worker acknowledgement. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 12.01 — DVR schedule lifecycle: happy path

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.02 — DVR schedule lifecycle: minimum boundary

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.03 — DVR schedule lifecycle: maximum supported size

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.04 — DVR schedule lifecycle: malformed input

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.05 — DVR schedule lifecycle: interrupted operation

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.06 — DVR schedule lifecycle: concurrent event

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.07 — DVR schedule lifecycle: repeated operation

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.08 — DVR schedule lifecycle: restart recovery

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.09 — DVR schedule lifecycle: permission or capability loss

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.10 — DVR schedule lifecycle: slow or stalled I/O

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.11 — DVR schedule lifecycle: data integrity

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.12 — DVR schedule lifecycle: privacy and telemetry

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.13 — DVR schedule lifecycle: resource exhaustion

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.14 — DVR schedule lifecycle: upgrade compatibility

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 12.15 — DVR schedule lifecycle: idempotency

**Setup and stimulus:** Start from multiple scheduled jobs with unique immutable identifiers; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the reservation state machine and exact-alarm receiver and include at least one representative case involving stale callbacks, duplicate IDs, encryption failure and reboot rearm. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert correct PREPARING to armed to STARTED transitions, with an explicit failure category if the input or environment is unsupported. Verify preservation of no overwritten credentials, no duplicate recording and recoverable row. Inspect schedule ID, expected state, alarm delivery and worker acknowledgement; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 13. DVR foreground-service teardown: component-level engineering contract

**Purpose and ownership.** This domain exists to make ordered stop, final write and database close reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the single worker and recording catalog lifecycle. Its representative input is a recorder whose network read is delayed during shutdown. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is ordered stop, final write and database close. Known hazards include onDestroy races, STOP during read and cancellation after capacity check. The system must preserve a safely finalized partial recording with no use-after-close. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect worker completion time, final row state and database close ordering. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 13.01 — DVR foreground-service teardown: happy path

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.02 — DVR foreground-service teardown: minimum boundary

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.03 — DVR foreground-service teardown: maximum supported size

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.04 — DVR foreground-service teardown: malformed input

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.05 — DVR foreground-service teardown: interrupted operation

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.06 — DVR foreground-service teardown: concurrent event

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.07 — DVR foreground-service teardown: repeated operation

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.08 — DVR foreground-service teardown: restart recovery

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.09 — DVR foreground-service teardown: permission or capability loss

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.10 — DVR foreground-service teardown: slow or stalled I/O

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.11 — DVR foreground-service teardown: data integrity

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.12 — DVR foreground-service teardown: privacy and telemetry

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.13 — DVR foreground-service teardown: resource exhaustion

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.14 — DVR foreground-service teardown: upgrade compatibility

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 13.15 — DVR foreground-service teardown: idempotency

**Setup and stimulus:** Start from a recorder whose network read is delayed during shutdown; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the single worker and recording catalog lifecycle and include at least one representative case involving onDestroy races, STOP during read and cancellation after capacity check. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert ordered stop, final write and database close, with an explicit failure category if the input or environment is unsupported. Verify preservation of a safely finalized partial recording with no use-after-close. Inspect worker completion time, final row state and database close ordering; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 14. SAF SD and USB recording: component-level engineering contract

**Purpose and ownership.** This domain exists to make direct streaming with capacity reserve and safe rename reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the SAF sink and persisted permission validator. Its representative input is a user-selected document-tree URI and controlled fake provider. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is direct streaming with capacity reserve and safe rename. Known hazards include revoked grant, missing free-space data, drive removal and rename failure. The system must preserve preserved metadata, no silent internal-storage fallback. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect URI grant state, reported free bytes, rename outcome and file classification. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 14.01 — SAF SD and USB recording: happy path

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.02 — SAF SD and USB recording: minimum boundary

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.03 — SAF SD and USB recording: maximum supported size

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.04 — SAF SD and USB recording: malformed input

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.05 — SAF SD and USB recording: interrupted operation

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.06 — SAF SD and USB recording: concurrent event

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.07 — SAF SD and USB recording: repeated operation

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.08 — SAF SD and USB recording: restart recovery

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.09 — SAF SD and USB recording: permission or capability loss

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.10 — SAF SD and USB recording: slow or stalled I/O

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.11 — SAF SD and USB recording: data integrity

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.12 — SAF SD and USB recording: privacy and telemetry

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.13 — SAF SD and USB recording: resource exhaustion

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.14 — SAF SD and USB recording: upgrade compatibility

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 14.15 — SAF SD and USB recording: idempotency

**Setup and stimulus:** Start from a user-selected document-tree URI and controlled fake provider; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the SAF sink and persisted permission validator and include at least one representative case involving revoked grant, missing free-space data, drive removal and rename failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert direct streaming with capacity reserve and safe rename, with an explicit failure category if the input or environment is unsupported. Verify preservation of preserved metadata, no silent internal-storage fallback. Inspect URI grant state, reported free bytes, rename outcome and file classification; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 15. Recording catalog and library: component-level engineering contract

**Purpose and ownership.** This domain exists to make consistent rows, protection flags and safe playback/deletion reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the catalog migrations and bounded recent queries. Its representative input is a disposable SQLite catalog with completed and partial recordings. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is consistent rows, protection flags and safe playback/deletion. Known hazards include database upgrades, collisions, missing files and malformed URI. The system must preserve protected recordings and unrelated documents. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect row count, schema version, query bounds and deletion decision. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 15.01 — Recording catalog and library: happy path

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.02 — Recording catalog and library: minimum boundary

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.03 — Recording catalog and library: maximum supported size

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.04 — Recording catalog and library: malformed input

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.05 — Recording catalog and library: interrupted operation

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.06 — Recording catalog and library: concurrent event

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.07 — Recording catalog and library: repeated operation

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.08 — Recording catalog and library: restart recovery

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.09 — Recording catalog and library: permission or capability loss

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.10 — Recording catalog and library: slow or stalled I/O

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.11 — Recording catalog and library: data integrity

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.12 — Recording catalog and library: privacy and telemetry

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.13 — Recording catalog and library: resource exhaustion

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.14 — Recording catalog and library: upgrade compatibility

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 15.15 — Recording catalog and library: idempotency

**Setup and stimulus:** Start from a disposable SQLite catalog with completed and partial recordings; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the catalog migrations and bounded recent queries and include at least one representative case involving database upgrades, collisions, missing files and malformed URI. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert consistent rows, protection flags and safe playback/deletion, with an explicit failure category if the input or environment is unsupported. Verify preservation of protected recordings and unrelated documents. Inspect row count, schema version, query bounds and deletion decision; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 16. Autonomous diagnostic runner: component-level engineering contract

**Purpose and ownership.** This domain exists to make objective PASS/FAIL/BLOCKED/NOT RUN evidence reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the app-local self-test engine and private report writer. Its representative input is synthetic canaries isolated from user media and credentials. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is objective PASS/FAIL/BLOCKED/NOT RUN evidence. Known hazards include interrupted report, log cap, failed fixture and unavailable hardware. The system must preserve unmodified user sources, playback and recordings. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect test ID, timestamps, environment, assertion counts and PSS. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 16.01 — Autonomous diagnostic runner: happy path

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.02 — Autonomous diagnostic runner: minimum boundary

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.03 — Autonomous diagnostic runner: maximum supported size

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.04 — Autonomous diagnostic runner: malformed input

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.05 — Autonomous diagnostic runner: interrupted operation

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.06 — Autonomous diagnostic runner: concurrent event

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.07 — Autonomous diagnostic runner: repeated operation

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.08 — Autonomous diagnostic runner: restart recovery

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.09 — Autonomous diagnostic runner: permission or capability loss

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.10 — Autonomous diagnostic runner: slow or stalled I/O

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.11 — Autonomous diagnostic runner: data integrity

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.12 — Autonomous diagnostic runner: privacy and telemetry

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.13 — Autonomous diagnostic runner: resource exhaustion

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.14 — Autonomous diagnostic runner: upgrade compatibility

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 16.15 — Autonomous diagnostic runner: idempotency

**Setup and stimulus:** Start from synthetic canaries isolated from user media and credentials; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the app-local self-test engine and private report writer and include at least one representative case involving interrupted report, log cap, failed fixture and unavailable hardware. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert objective PASS/FAIL/BLOCKED/NOT RUN evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of unmodified user sources, playback and recordings. Inspect test ID, timestamps, environment, assertion counts and PSS; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 17. Lifecycle and process recovery: component-level engineering contract

**Purpose and ownership.** This domain exists to make restoration without stale overwrite or oversized Bundles reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the startup reload worker and saved-state policy. Its representative input is an Activity restart or process kill with a previously validated source. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is restoration without stale overwrite or oversized Bundles. Known hazards include rotation, rapid manual replacement, background stop and corrupted state. The system must preserve validated encrypted source and stable UI responsiveness. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect reload count, source selection, query length and player release. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 17.01 — Lifecycle and process recovery: happy path

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.02 — Lifecycle and process recovery: minimum boundary

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.03 — Lifecycle and process recovery: maximum supported size

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.04 — Lifecycle and process recovery: malformed input

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.05 — Lifecycle and process recovery: interrupted operation

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.06 — Lifecycle and process recovery: concurrent event

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.07 — Lifecycle and process recovery: repeated operation

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.08 — Lifecycle and process recovery: restart recovery

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.09 — Lifecycle and process recovery: permission or capability loss

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.10 — Lifecycle and process recovery: slow or stalled I/O

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.11 — Lifecycle and process recovery: data integrity

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.12 — Lifecycle and process recovery: privacy and telemetry

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.13 — Lifecycle and process recovery: resource exhaustion

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.14 — Lifecycle and process recovery: upgrade compatibility

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 17.15 — Lifecycle and process recovery: idempotency

**Setup and stimulus:** Start from an Activity restart or process kill with a previously validated source; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the startup reload worker and saved-state policy and include at least one representative case involving rotation, rapid manual replacement, background stop and corrupted state. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert restoration without stale overwrite or oversized Bundles, with an explicit failure category if the input or environment is unsupported. Verify preservation of validated encrypted source and stable UI responsiveness. Inspect reload count, source selection, query length and player release; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 18. Low-memory performance: component-level engineering contract

**Purpose and ownership.** This domain exists to make stable heap, PSS and CPU without UI stalls reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the parser, indexes, recycled views and bounded recorder. Its representative input is a 1 GiB or 2 GiB target profile with large synthetic guide. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is stable heap, PSS and CPU without UI stalls. Known hazards include repeated imports, scrolling, long recording and decoder churn. The system must preserve an operable guide and a correctly stopped recorder under pressure. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect peak PSS, GC count, jank, CPU time and battery delta. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 18.01 — Low-memory performance: happy path

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.02 — Low-memory performance: minimum boundary

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.03 — Low-memory performance: maximum supported size

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.04 — Low-memory performance: malformed input

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.05 — Low-memory performance: interrupted operation

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.06 — Low-memory performance: concurrent event

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.07 — Low-memory performance: repeated operation

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.08 — Low-memory performance: restart recovery

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.09 — Low-memory performance: permission or capability loss

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.10 — Low-memory performance: slow or stalled I/O

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.11 — Low-memory performance: data integrity

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.12 — Low-memory performance: privacy and telemetry

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.13 — Low-memory performance: resource exhaustion

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.14 — Low-memory performance: upgrade compatibility

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 18.15 — Low-memory performance: idempotency

**Setup and stimulus:** Start from a 1 GiB or 2 GiB target profile with large synthetic guide; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the parser, indexes, recycled views and bounded recorder and include at least one representative case involving repeated imports, scrolling, long recording and decoder churn. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert stable heap, PSS and CPU without UI stalls, with an explicit failure category if the input or environment is unsupported. Verify preservation of an operable guide and a correctly stopped recorder under pressure. Inspect peak PSS, GC count, jank, CPU time and battery delta; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 19. CI and release packaging: component-level engineering contract

**Purpose and ownership.** This domain exists to make reproducible artifact, independent checks and traceable evidence reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the Gradle build and Android emulator workflow. Its representative input is a clean checkout at an exact immutable commit. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is reproducible artifact, independent checks and traceable evidence. Known hazards include missing SDK, flaky downloads, stale artifact and docs-only changes. The system must preserve no false green build and no published unverified release. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect commit SHA, job status, APK digest and report contents. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 19.01 — CI and release packaging: happy path

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.02 — CI and release packaging: minimum boundary

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.03 — CI and release packaging: maximum supported size

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.04 — CI and release packaging: malformed input

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.05 — CI and release packaging: interrupted operation

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.06 — CI and release packaging: concurrent event

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.07 — CI and release packaging: repeated operation

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.08 — CI and release packaging: restart recovery

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.09 — CI and release packaging: permission or capability loss

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.10 — CI and release packaging: slow or stalled I/O

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.11 — CI and release packaging: data integrity

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.12 — CI and release packaging: privacy and telemetry

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.13 — CI and release packaging: resource exhaustion

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.14 — CI and release packaging: upgrade compatibility

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 19.15 — CI and release packaging: idempotency

**Setup and stimulus:** Start from a clean checkout at an exact immutable commit; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the Gradle build and Android emulator workflow and include at least one representative case involving missing SDK, flaky downloads, stale artifact and docs-only changes. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert reproducible artifact, independent checks and traceable evidence, with an explicit failure category if the input or environment is unsupported. Verify preservation of no false green build and no published unverified release. Inspect commit SHA, job status, APK digest and report contents; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## 20. Branding and accessible UX: component-level engineering contract

**Purpose and ownership.** This domain exists to make legible guide, clear focus, usable navigation and complete branding reliable in the TunerOS Android TV/mobile environment. The system boundary under examination is the launcher resources, themes and accessible controls. Its representative input is a TV remote and phone with the selected TunerOS branding kit. The implementation must be isolated enough to exercise without connecting to a private provider, altering a real recording, or requiring an end user to execute test scripts.

**Preconditions.** Establish the exact commit, clean fixture namespace, known Android/API or JVM environment, and deterministic expected output before invoking the component. Record which platform is used. Keep private credentials out of fixture text; use recognizable synthetic canaries only. When an assertion requires real hardware or provider cooperation, report BLOCKED or NOT RUN rather than substituting a mock and calling it a device PASS.

**Core invariant.** The expected result is legible guide, clear focus, usable navigation and complete branding. Known hazards include small screen, overscan, large font, missing art and contrast failure. The system must preserve working Classic Cable default without cropped essential controls. Resource budgets should be stated before the test and measured in context rather than guessed from source code.

**Observability.** Collect focus traversal, layout bounds, text contrast and asset provenance. Capture monotonic elapsed time, wall time, relevant platform build and a stable test identifier. Keep report output bounded and explicitly separate expected refusal from a crash. Include the exact fixture digest or seed so another engineer can reproduce the same behavior.

**Failure triage.** If the assertion fails, preserve the fixture and redacted trace, identify the first incorrect state transition, and write a regression test before fixing it. Check whether the root cause is in the pure core, Android adapter, third-party API behavior or test environment. Never retry an identical failure more than twice without changing the diagnostic approach. Re-run neighboring tests after correction, especially privacy, cancellation and resource bounds.

### 20.01 — Branding and accessible UX: happy path

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; use a small deterministic valid fixture, then compare the observed result against a reference oracle. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.02 — Branding and accessible UX: minimum boundary

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; use empty and single-record inputs and assert an explicit no-data state rather than accidental success. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.03 — Branding and accessible UX: maximum supported size

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; populate the documented capacity and verify that one extra item produces a bounded warning or rejection. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.04 — Branding and accessible UX: malformed input

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; corrupt a syntax token at the beginning, middle and end while retaining valid surrounding records. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.05 — Branding and accessible UX: interrupted operation

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; cancel after the first durable write and before finalization, then inspect recovery state. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.06 — Branding and accessible UX: concurrent event

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; interleave a user action with a background callback and ensure the newer state cannot be overwritten. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.07 — Branding and accessible UX: repeated operation

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; repeat the action twenty times in the same process and assert no increasing resource baseline. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.08 — Branding and accessible UX: restart recovery

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; terminate the app after persistence and reopen using the same isolated fixture. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.09 — Branding and accessible UX: permission or capability loss

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; remove the required permission or simulate an unavailable platform capability. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.10 — Branding and accessible UX: slow or stalled I/O

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; delay reads and return zero progress repeatedly to test timeout and cancellation. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.11 — Branding and accessible UX: data integrity

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; compare exact identifiers, byte counts or timeline selections against a deterministic oracle. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.12 — Branding and accessible UX: privacy and telemetry

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; inject credential-shaped canaries and scan UI, logs, exports and failure messages for disclosure. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.13 — Branding and accessible UX: resource exhaustion

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; restrict available storage, heap or decoder capacity and verify graceful refusal or partial result. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.14 — Branding and accessible UX: upgrade compatibility

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; migrate a fixture from the preceding schema/configuration while retaining existing data. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**

### 20.15 — Branding and accessible UX: idempotency

**Setup and stimulus:** Start from a TV remote and phone with the selected TunerOS branding kit; deliver the same request or callback twice and assert only one durable side effect. For this domain specifically, exercise the launcher resources, themes and accessible controls and include at least one representative case involving small screen, overscan, large font, missing art and contrast failure. Run in an isolated scratch namespace; record the fixture hash, test seed, Android API level or JVM version, and the exact code commit.

**Expected result and acceptance:** Assert legible guide, clear focus, usable navigation and complete branding, with an explicit failure category if the input or environment is unsupported. Verify preservation of working Classic Cable default without cropped essential controls. Inspect focus traversal, layout bounds, text contrast and asset provenance; a green process exit without these assertions is insufficient. Check that no private source URL, header, or media content appears in diagnostics.

**Recovery and regression:** If interrupted or rejected, retry only after resetting the scratch fixture and determining whether the outcome is deterministic. Ensure cleanup releases file handles, workers and player resources; do not delete user data. Add a permanent automated regression with the same seed and expected result. **Evidence at dossier creation: NOT RUN unless independently linked to an exact-commit existing test.**


## Cross-domain integration and release decision matrix

The previous cases isolate components; release acceptance must also cover the seams. A successful M3U import must lead to stable guide rows; an XMLTV import must map to source-scoped channels; search must not destroy focus; channel activation must not leave multiple Media3 instances; Record Now must snapshot the correct encrypted source; an alarm request must not count as a started recording; an HLS segment must not be marked complete before the sink is durably finalized; a disconnected SAF tree must not trigger a silent path switch; and a cancelled worker must not write to a closed SQLite helper. Each seam needs at least one deterministic synthetic test and, when possible, a real Android instrumentation observation.

Before a release candidate, capture: immutable source SHA, build artifact SHA-256, dependency lock/version record, Android manifest permission review, supported and unsupported stream formats, synthetic self-test summary, actual hardware matrix, maximum sampled PSS and peak allocations under stated workloads, cold/warm start timing, channel-switch timing, recording byte-integrity evidence, alarm delivery evidence, external storage fault evidence, and accessibility/remote focus observations. If any high-risk item lacks evidence, mark release BLOCKED rather than claiming completion.

## Required engineering ledger format

For each change, create one ledger entry with: unique task ID; user-visible problem; technical root cause; affected source paths; original design rationale; alternative fixes considered and rejected; chosen implementation; memory/CPU/I/O implications; tests added; test commands; exact commit; CI run URL; emulator/device model and API; evidence status; known limitations; rollback steps; follow-on task; and owner or responsible agent. An ADR must state whether it is accepted, superseded or proposed. A completed PR is not itself proof of runtime acceptance.

## Autonomous successor boot sequence

1. Read `README.md`, `docs/HANDOFF.md`, `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/ON_DEVICE_QA.md`, the audit findings, provenance ledger and this dossier.
2. Inspect actual `main` SHA, all open PRs/issues, CI jobs, build artifacts and current source files. Compare against the last documented SHA and record divergence.
3. Identify the highest-risk unverified release gate. Prefer device playback, alarm delivery, SAF faults, worker teardown and measured 1/2 GiB memory before speculative UI features.
4. Implement one bounded, reviewable change. Run fast Kotlin tests, Android APK build, emulator QA and the relevant focused fault fixture.
5. Investigate failure root causes; never paper over a failing assertion or relabel BLOCKED as PASS.
6. Update the single authoritative ledger, user manual, roadmap, feature status and this handoff only with verified facts. Preserve the exact continuation command and remaining blockers.

## Source references and provenance

Canonical code: https://github.com/DevTunaSoft/TunerOS. Documentation consulted: `README.md`, `docs/HANDOFF.md`, `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/ON_DEVICE_QA.md`, `docs/USER_MANUAL.md`, `docs/AUDIT_FINDINGS_2026-11-10.md`, `docs/THIRD_PARTY_PROVENANCE.md`, `.github/workflows/android.yml`. The guide's timeline concepts have documented Apache-2.0 attribution to Egeniq; additional code reuse must be reviewed separately. Earlier browser V8/V9 work is not assumed to exist in the native repository.
