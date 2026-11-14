# TunerOS — Deep Engineering Book, Volume III

**Purpose:** successor-grade architecture, decision, verification and failure-recovery reference.
**Evidence boundary:** This volume combines repository-confirmed descriptions with explicit *proposed* engineering contracts. Proposed tests are not execution evidence.
**Repository:** https://github.com/DevTunaSoft/TunerOS
**Baseline evidence:** docs/FEATURE_STATUS.md reports CI run 43555173671 at `a62671c` (23 local/synthetic PASS, 0 FAIL, 1 BLOCKED, 12 NOT RUN); newer commits require fresh verification.

## How to read this volume
The first two volumes preserve the original history and broad contracts. This volume supplies decision-level explanations, precise failure invariants, implementation review prompts, and verification procedures. A successor must inspect current code before using any proposed interface or workflow.

## Evidence taxonomy
- **CONFIRMED SOURCE DESCRIPTION:** a statement supported by inspected repository documentation, not independently recompiled in this session.
- **HISTORICAL CI PASS:** only the exact commit and Actions run cited, never subsequent changes.
- **PROPOSED CONTRACT:** desired implementation or QA behavior, not proof it exists.
- **UNVERIFIED:** no executed evidence for the claim in the present review.

## Repository-grounded technical anchors
- `settings.gradle.kts` includes `:core` and `:app`; Kotlin Android separation is intentional.
- `.github/workflows/android.yml` pins Java 17 and Gradle 8.9, builds `:core:test :app:assembleDebug`, and runs Android 35 emulator QA with a 2 GiB RAM / 256 MiB heap profile.
- `docs/FEATURE_STATUS.md` reports Android emulator synthetic/local checks and physical-device limitations; do not extrapolate its one-time PSS snapshots.
- `docs/ON_DEVICE_QA.md` explicitly prohibits synthetic tests from modifying user recordings, launching provider streams, or claiming unsupported environmental fault coverage.
- `docs/ROADMAP.md` prioritizes stable guide/playback/DVR and defers FastChannels serving and full multiview until acceptance.

## Chapter 01 — Playlist ingestion and channel identity

### Product and engineering motivation
M3U input is untrusted, often inconsistent, and may contain provider credentials.

### Existing direction and proposed contract
Bounded incremental line reading; parse only required EXTINF attributes; preserve stable source-scoped SHA-256 IDs; retain channel order and guide associations.

### Failure and adversarial model
Truncated lines, duplicate tvg-id, empty URLs, unexpected encodings, invalid attributes, 25k-channel feeds, source reloads.

### Non-negotiable invariants
Never log raw URLs; do not construct a per-channel metadata map when only four attributes are consumed; keep cap warnings visible.

### Acceptance evidence required
Channel IDs must be stable across identical imports but differ when source/stream identity differs; partial import must say it is partial.

### Design decision record
**ADR-001 — Preserve bounded, explicit behavior for playlist ingestion and channel identity.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-01-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-01-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-01-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-01-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-01-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-01-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-01-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-01-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for playlist ingestion and channel identity, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-001` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 02 — XMLTV import and parser trust boundary

### Product and engineering motivation
Guide feeds may be gzip-compressed, huge, malformed, timezone-ambiguous, or intentionally hostile XML.

### Existing direction and proposed contract
Stream through Android-compatible SAX reader; reject DTD and external entities; cap depth, text, programmes and bytes; preserve future schedules.

### Failure and adversarial model
XXE, entity expansion, 65-deep XML, malformed timestamps, offsetless timestamps, overlapping shows, duplicate channel IDs.

### Non-negotiable invariants
Do not reinstate unsupported vendor SAX flags; do not silently drop offsetless times; descriptions not displayed should not be retained.

### Acceptance evidence required
Android emulator parses valid fixture and rejects DTD/depth attack; UTC fallback emits warning; capped import remains honest.

### Design decision record
**ADR-002 — Preserve bounded, explicit behavior for xmltv import and parser trust boundary.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-02-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-02-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-02-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-02-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-02-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-02-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-02-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-02-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for xmltv import and parser trust boundary, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-002` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 03 — EPG indexes and temporal semantics

### Product and engineering motivation
Per-keystroke full scans previously risked freezing guide UI on large datasets.

### Existing direction and proposed contract
Sort per-channel programmes, use binary-search time windows, overlap-aware prefix maxima only where needed, compute now once per refresh.

### Failure and adversarial model
Adjacent programme boundaries, overlaps, invalid intervals, DST transition, long gaps, future-only channels.

### Non-negotiable invariants
Avoid per-row linear now lookup and per-minute dataset rebind; do not interpret no current programme as no EPG.

### Acceptance evidence required
Randomized oracle equivalence for window lookup, measured interaction latency on constrained Android TV.

### Design decision record
**ADR-003 — Preserve bounded, explicit behavior for epg indexes and temporal semantics.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-03-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-03-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-03-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-03-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-03-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-03-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-03-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-03-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for epg indexes and temporal semantics, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-003` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 04 — Guide virtualization and remote navigation

### Product and engineering motivation
A cable-style three-hour grid must remain usable with a D-pad and a large channel catalog.

### Existing direction and proposed contract
Recycle visible rows, draw timeline segments instead of inflating a view per programme, maintain focus anchor and half-hour window shift.

### Failure and adversarial model
Empty guide, gaps, very long programme, remote repeat keys, orientation change, touch vs remote focus.

### Non-negotiable invariants
Do not materialize all channels or schedule cells; never steal focus from active playback on background refresh.

### Acceptance evidence required
D-pad traverses programme and channel targets, Enter launches selected stream, focus survives time-window shift.

### Design decision record
**ADR-004 — Preserve bounded, explicit behavior for guide virtualization and remote navigation.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-04-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-04-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-04-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-04-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-04-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-04-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-04-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-04-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for guide virtualization and remote navigation, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-004` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 05 — Search, filtering and EPG coverage

### Product and engineering motivation
Search and hide-without-EPG must remain optional and semantically correct.

### Existing direction and proposed contract
Debounce text input, cache filtered results, use indexed schedule existence rather than current airing, preserve original channel order.

### Failure and adversarial model
Rapid typing, empty query, diacritics, future-only guide, missing tvg-id, partially imported guide.

### Non-negotiable invariants
Do not filter out future-only channels when an optional retention window is introduced.

### Acceptance evidence required
Search updates without ANR; hide toggle changes only intended rows and can be disabled.

### Design decision record
**ADR-005 — Preserve bounded, explicit behavior for search, filtering and epg coverage.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-05-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-05-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-05-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-05-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-05-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-05-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-05-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-05-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for search, filtering and epg coverage, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-005` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 06 — Source transport and redirect policy

### Product and engineering motivation
Remote playlist and guide downloads cross a credential-bearing network boundary.

### Existing direction and proposed contract
Bound decompressed payload; sniff gzip; count skip/read; reject HTTPS-to-HTTP downgrade; close streams on every setup error.

### Failure and adversarial model
Redirect loops, compressed bombs, slow reads, zero-byte reads, interrupted TLS, unsupported content type.

### Non-negotiable invariants
Never allow skip to bypass byte caps; do not log credentials in URLs or headers.

### Acceptance evidence required
Synthetic HTTP fixtures prove byte caps, redirect rejection, gzip handling and cleanup.

### Design decision record
**ADR-006 — Preserve bounded, explicit behavior for source transport and redirect policy.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-06-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-06-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-06-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-06-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-06-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-06-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-06-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-06-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for source transport and redirect policy, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-006` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 07 — Encrypted source storage and migration

### Product and engineering motivation
Source URLs often embed passwords; previous plaintext preferences must be migrated without losing working configuration.

### Existing direction and proposed contract
Android Keystore AES-GCM, migrate on bounded worker, remove plaintext only after verified encrypted write; preserve legacy on failure.

### Failure and adversarial model
Keystore invalidation, interrupted migration, app reinstall, corrupt ciphertext, simultaneous user edit.

### Non-negotiable invariants
Never do crypto on UI thread; never overwrite a freshly typed URL with stale recovered text.

### Acceptance evidence required
Migration and failure injection demonstrate no silent source loss and no plaintext log/database leakage.

### Design decision record
**ADR-007 — Preserve bounded, explicit behavior for encrypted source storage and migration.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-07-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-07-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-07-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-07-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-07-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-07-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-07-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-07-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for encrypted source storage and migration, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-007` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 08 — Player lifecycle and stream compatibility

### Product and engineering motivation
Media3 playback spans codecs, manifests, redirects, provider headers and hardware decoder limits.

### Existing direction and proposed contract
Reuse one player when headers match; release/recreate when header identity changes; release onStop; observe first decoded frame separately.

### Failure and adversarial model
Rapid channel changes, HLS/DASH/progressive failures, 401/403, audio-only streams, background/foreground transitions.

### Non-negotiable invariants
Do not equate isPlaying with video frame rendered; do not spawn retry loops or extra decoders by default.

### Acceptance evidence required
Real-device first-frame, audio, switching, decoder release and lifecycle evidence.

### Design decision record
**ADR-008 — Preserve bounded, explicit behavior for player lifecycle and stream compatibility.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-08-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-08-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-08-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-08-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-08-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-08-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-08-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-08-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for player lifecycle and stream compatibility, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-008` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 09 — Stream Doctor and privacy-safe diagnostics

### Product and engineering motivation
Users need actionable errors without exposing provider credentials or making speculative automatic repairs.

### Existing direction and proposed contract
Map numeric Media3 errors to bounded categories, keep fixed-format event codes and one-shot timeout observations.

### Failure and adversarial model
Expired token, premium/parental restriction, bad MIME, missing segment, audio output failure, DNS outage.

### Non-negotiable invariants
Never log exception strings, stream URL, auth headers or raw manifests.

### Acceptance evidence required
Classifier unit tests and privacy audit of exported reports.

### Design decision record
**ADR-009 — Preserve bounded, explicit behavior for stream doctor and privacy-safe diagnostics.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-09-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-09-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-09-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-09-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-09-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-09-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-09-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-09-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for stream doctor and privacy-safe diagnostics, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-009` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 10 — Raw DVR capture

### Product and engineering motivation
A recording should write bytes to disk without growing heap with duration.

### Existing direction and proposed contract
One 64 KiB copy buffer, bounded progress updates, reserve check before/after reads, cancellation and partial-state finalization.

### Failure and adversarial model
Zero-progress reads, disk full, unknown content length, premature EOF, worker cancellation, destination failure.

### Non-negotiable invariants
Never buffer the whole stream, never mark partial output complete, never spin on zero-byte reads.

### Acceptance evidence required
File-backed synthetic byte-for-byte comparison and sustained hardware capture evidence.

### Design decision record
**ADR-010 — Preserve bounded, explicit behavior for raw dvr capture.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-10-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-10-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-10-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-10-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-10-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-10-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-10-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-10-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for raw dvr capture, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-010` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 11 — HLS DVR capture

### Product and engineering motivation
HLS recording is segment-oriented and vulnerable to sequence gaps, redirects, byte-range errors and changing playlists.

### Existing direction and proposed contract
Support explicit unencrypted MPEG-TS subset, deduplicate by media sequence, track live edge, reuse segment buffer, validate range and identity lengths.

### Failure and adversarial model
Playlist stalls, missing sequence, master variant, byte-range mismatch, 200 vs 206, gzip, token expiration, discontinuities.

### Non-negotiable invariants
Fail closed for AES-encrypted HLS, fMP4, external renditions and DASH until supported; do not concatenate incompatible segments.

### Acceptance evidence required
Synthetic manifest/HTTP tests and real provider format matrix with media integrity checks.

### Design decision record
**ADR-011 — Preserve bounded, explicit behavior for hls dvr capture.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-11-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-11-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-11-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-11-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-11-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-11-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-11-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-11-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for hls dvr capture, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-011` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 12 — Recording schedule transactional lifecycle

### Product and engineering motivation
Future jobs must not lose credentials, overwrite existing schedules or claim success before the service accepts work.

### Existing direction and proposed contract
Persist encrypted source before activation; reserve unique ID; arm exact alarm when permitted; acknowledge from recorder service; isolate re-arm failures.

### Failure and adversarial model
Permission revoked, >200 pending jobs, reboot, duplicate IDs, race between cancel and alarm, stale dispatch.

### Non-negotiable invariants
Do not silently downgrade exact to unreliable approximate timing; do not overwrite prior encrypted schedule.

### Acceptance evidence required
State-machine transition tests, reboot rig observations, delivered-alarm evidence.

### Design decision record
**ADR-012 — Preserve bounded, explicit behavior for recording schedule transactional lifecycle.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-12-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-12-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-12-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-12-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-12-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-12-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-12-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-12-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for recording schedule transactional lifecycle, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-012` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 13 — Foreground recorder teardown

### Product and engineering motivation
Android may destroy the service while a worker is still copying bytes.

### Existing direction and proposed contract
Order cancellation, worker completion, file finalization and catalog close; avoid blocking main thread; preserve partial status.

### Failure and adversarial model
onDestroy during read, rapid stop/start, receiver races, exception in finalizer, stale callback.

### Non-negotiable invariants
Never close SQLite underneath a still-running recorder; never report completion before durable finalization.

### Acceptance evidence required
Fault-injected shutdown ordering and no resource leaks after repeated sessions.

### Design decision record
**ADR-013 — Preserve bounded, explicit behavior for foreground recorder teardown.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-13-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-13-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-13-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-13-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-13-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-13-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-13-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-13-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for foreground recorder teardown, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-013` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 14 — SAF USB/SD destinations

### Product and engineering motivation
Removable drives and document providers have different guarantees from app-private files.

### Existing direction and proposed contract
Persist tree grants, validate writable local provider, inspect available bytes, create unique documents, stream directly, preserve catalog on removal.

### Failure and adversarial model
Drive unplug, grant revoked, provider returns unknown free space, duplicate names, stale URI, zero-byte write.

### Non-negotiable invariants
No broad storage permission; no silent fallback to internal storage; no deleting media outside selected owned destination.

### Acceptance evidence required
Authorized hardware rig tests removal/reconnect and confirms partial media/catalog consistency.

### Design decision record
**ADR-014 — Preserve bounded, explicit behavior for saf usb/sd destinations.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-14-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-14-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-14-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-14-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-14-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-14-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-14-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-14-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for saf usb/sd destinations, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-014` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 15 — Recording library and database

### Product and engineering motivation
A useful DVR needs reliable history, protect/delete controls and bounded browsing.

### Existing direction and proposed contract
SQLite catalog separate from encrypted source secrets; bounded recent query; versioned migrations; path/URI validation before delete.

### Failure and adversarial model
Duplicate recording IDs, corrupt row, interrupted migration, protected delete, missing file, catalog/file disagreement.

### Non-negotiable invariants
Never expose source secrets in database; never delete an arbitrary path supplied by a malformed row.

### Acceptance evidence required
Migration tests, protected-recording invariants, list latency and bounded query memory.

### Design decision record
**ADR-015 — Preserve bounded, explicit behavior for recording library and database.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-15-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-15-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-15-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-15-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-15-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-15-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-15-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-15-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for recording library and database, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-015` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 16 — Autonomous in-app QA

### Product and engineering motivation
Users should not be required to run manual test scripts; QA must not damage their content.

### Existing direction and proposed contract
Production runs small safe offline checks; explicit debug CI runs stress/HTTP/SQLite fixtures; capped private report with honest status.

### Failure and adversarial model
Interrupted test, permission absent, no provider configured, low storage, concurrent active recording, CI missing report.

### Non-negotiable invariants
Never play user streams or delete user recordings during synthetic tests; never label NOT RUN as PASS.

### Acceptance evidence required
Emulator artifact with counts and exact assertions; physical test rig for environmental scenarios.

### Design decision record
**ADR-016 — Preserve bounded, explicit behavior for autonomous in-app qa.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-16-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-16-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-16-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-16-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-16-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-16-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-16-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-16-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for autonomous in-app qa, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-016` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 17 — Activity/process recovery

### Product and engineering motivation
Android can kill the app or recreate Activity without preserving large in-memory guide data.

### Existing direction and proposed contract
Keep Bundle minimal: bounded search text and guide time; encrypted validated URLs reload on worker; explicit import supersedes auto-recovery.

### Failure and adversarial model
Rotation, process death, offline startup, failed source reload, user editing while recovery completes.

### Non-negotiable invariants
Do not serialize whole guide into Bundle or replace saved valid source after failed import.

### Acceptance evidence required
Automated recreation and process-death scenarios with source and UI state assertions.

### Design decision record
**ADR-017 — Preserve bounded, explicit behavior for activity/process recovery.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-17-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-17-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-17-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-17-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-17-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-17-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-17-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-17-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for activity/process recovery, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-017` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 18 — Memory and CPU budgets

### Product and engineering motivation
The target includes resource-constrained TV hardware, so allocations and background work are first-class design risks.

### Existing direction and proposed contract
Bound parser input and retained fields, virtualize rows, reuse buffers, no polling when hidden, sample PSS/heap/CPU under repeatable workloads.

### Failure and adversarial model
1/2 GiB RAM, 25k channels, 250k programmes, 24-hour capture, rapid navigation and switching.

### Non-negotiable invariants
Do not treat one emulator PSS sample as a production ceiling; avoid redundant maps/arrays and extra player instances.

### Acceptance evidence required
Median/p95 and peak measurements with device/build/fixture metadata; no regressions versus baseline.

### Design decision record
**ADR-018 — Preserve bounded, explicit behavior for memory and cpu budgets.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-18-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-18-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-18-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-18-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-18-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-18-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-18-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-18-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for memory and cpu budgets, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-018` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 19 — CI, packaging and reproducibility

### Product and engineering motivation
A source-level claim is not a built APK and a green synthetic emulator is not physical-device acceptance.

### Existing direction and proposed contract
JDK17 Gradle8.9 SDK35; core unit tests, debug APK, Android35 emulator self-test; preserve artifacts and commit/run linkage.

### Failure and adversarial model
Missing wrapper, artifact retention, emulator infrastructure failure, stale branch, doc-only push.

### Non-negotiable invariants
Do not claim CI passed for newer commit than tested; never bypass failing checks.

### Acceptance evidence required
Commit SHA, Actions run URL, job outcome, artifact digest and executed test counts recorded.

### Design decision record
**ADR-019 — Preserve bounded, explicit behavior for ci, packaging and reproducibility.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-19-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-19-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-19-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-19-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-19-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-19-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-19-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-19-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for ci, packaging and reproducibility, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-019` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 20 — Product design, branding and accessibility

### Product and engineering motivation
The product aims to feel like a polished classic cable receiver, not a bare playlist tester.

### Existing direction and proposed contract
Classic Cable default; accessible focus targets, readable guide, clear stream errors; integrate approved branding assets after inspection.

### Failure and adversarial model
Small TV screens, overscan, remote-only control, screen reader, high contrast, color-blind states.

### Non-negotiable invariants
Do not claim final branding applied when placeholders remain; do not add heavy image assets without size review.

### Acceptance evidence required
Automated focus/accessibility probes and physical TV readability observations.

### Design decision record
**ADR-020 — Preserve bounded, explicit behavior for product design, branding and accessibility.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-20-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-20-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-20-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-20-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-20-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-20-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-20-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-20-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for product design, branding and accessibility, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-020` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 21 — Multiview and audio ownership

### Product and engineering motivation
User-requested DirecTV-style multiview is not equivalent to a capacity policy helper.

### Existing direction and proposed contract
Start with two decoder tiles and explicit single audio owner; check provider concurrency and hardware decoder capacity; release on exit.

### Failure and adversarial model
Decoder exhaustion, focus changes, audio handoff, mixed HLS/DASH, low-memory device, app backgrounding.

### Non-negotiable invariants
No eager four-player creation; never exceed known provider stream limits; degrade to single playback safely.

### Acceptance evidence required
Two-tile device acceptance, audio owner invariants, PSS/CPU/decoder measurements; mark unimplemented until code exists.

### Design decision record
**ADR-021 — Preserve bounded, explicit behavior for multiview and audio ownership.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-21-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-21-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-21-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-21-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-21-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-21-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-21-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-21-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for multiview and audio ownership, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-021` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 22 — Guide Doctor and mapping repair

### Product and engineering motivation
Unmatched XMLTV IDs leave schedules blank even when provider feeds contain useful programme data.

### Existing direction and proposed contract
Offer source-scoped, explainable mapping candidates and explicit user approval; persist reversible overrides; no silent global guessing.

### Failure and adversarial model
Duplicate names, timezones, renamed channels, conflicting EPG sources, stale overrides.

### Non-negotiable invariants
Never alter provider original identifiers or claim matching certainty without evidence.

### Acceptance evidence required
Deterministic matching fixtures and reversible migration tests; currently planned.

### Design decision record
**ADR-022 — Preserve bounded, explicit behavior for guide doctor and mapping repair.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-22-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-22-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-22-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-22-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-22-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-22-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-22-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-22-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for guide doctor and mapping repair, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-022` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 23 — Series rules and conflict arbitration

### Product and engineering motivation
Single-recording foundation is not a complete household DVR.

### Existing direction and proposed contract
Model recurring episode identity, rule priority, storage reserve and overlap conflicts before enabling unattended capture.

### Failure and adversarial model
Duplicate episode, schedule drift, missing EPG, clock skew, interrupted rules, provider stream limits.

### Non-negotiable invariants
Never silently cancel another recording or promise parallel captures unsupported by device.

### Acceptance evidence required
Conflict-policy oracle, schedule persistence and real-device alarm acceptance; currently planned.

### Design decision record
**ADR-023 — Preserve bounded, explicit behavior for series rules and conflict arbitration.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-23-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-23-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-23-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-23-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-23-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-23-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-23-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-23-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for series rules and conflict arbitration, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-023` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 24 — Timeshift and crash-consistent ring buffers

### Product and engineering motivation
Live pause/rewind requires controlled storage lifecycle and seekable indexing.

### Existing direction and proposed contract
Design bounded disk-backed ring with monotonic segment IDs, atomic index checkpoint and user-controlled capacity.

### Failure and adversarial model
Disk full, process kill, rapid seek, time discontinuity, storage removal, live edge rollover.

### Non-negotiable invariants
Do not allocate ring in RAM; do not delete protected recordings when freeing timeshift space.

### Acceptance evidence required
Recovery invariants, bounded disk growth and sustained playback/seek tests; currently planned.

### Design decision record
**ADR-024 — Preserve bounded, explicit behavior for timeshift and crash-consistent ring buffers.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-24-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-24-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-24-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-24-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-24-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-24-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-24-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-24-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for timeshift and crash-consistent ring buffers, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-024` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 25 — Open-source licensing and provenance

### Product and engineering motivation
Ideas from public repositories can inspire implementation but copied code requires compatible licenses and attribution.

### Existing direction and proposed contract
Maintain source URL, commit, license, modified files, notices and tests in provenance ledger; prefer independent clean-room implementations.

### Failure and adversarial model
License absent, GPL incompatibility, transitive notices, copied UI art, unlicensed channel playlists.

### Non-negotiable invariants
Never treat public visibility as permission to copy; do not distribute unauthorized channels.

### Acceptance evidence required
Provenance review with legal/license owner before release.

### Design decision record
**ADR-025 — Preserve bounded, explicit behavior for open-source licensing and provenance.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-25-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-25-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-25-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-25-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-25-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-25-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-25-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-25-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for open-source licensing and provenance, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-025` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 26 — Security, privacy and abuse boundaries

### Product and engineering motivation
Provider credentials, local files, URLs and exported diagnostics are sensitive trust boundaries.

### Existing direction and proposed contract
Keystore, redacted fixed-code logs, URL scheme validation, bounded network inputs, scoped SAF, no unsolicited telemetry.

### Failure and adversarial model
SSRF to local endpoints, HTTP downgrade, malicious playlist, path traversal, leaked headers, shared report.

### Non-negotiable invariants
Never persist raw source URL in plaintext SQLite or include it in issue reports.

### Acceptance evidence required
Threat-model review and negative tests for each boundary.

### Design decision record
**ADR-026 — Preserve bounded, explicit behavior for security, privacy and abuse boundaries.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-26-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-26-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-26-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-26-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-26-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-26-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-26-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-26-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for security, privacy and abuse boundaries, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-026` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Chapter 27 — Release and support operations

### Product and engineering motivation
A product is not finished when a debug APK exists; users need updates, migrations and recovery instructions.

### Existing direction and proposed contract
Signed release pipeline, versioning, artifact provenance, support diagnostics, rollback/migration strategy and known limitations.

### Failure and adversarial model
Signing key loss, incompatible schema upgrade, failed installation, unsupported device, missing exact-alarm permission.

### Non-negotiable invariants
Never ship as production-ready while core hardware gates remain NOT RUN.

### Acceptance evidence required
Release checklist signed by evidence, install/upgrade smoke, crash-free soak and documented support path.

### Design decision record
**ADR-027 — Preserve bounded, explicit behavior for release and support operations.** The reason for this choice is not merely speed: it reduces ambiguity about data ownership, lifetime, recovery and user-visible truth. Alternative designs that silently retry, cache unlimited content, or treat partial results as success make later incidents harder to diagnose. Any replacement must demonstrate equal semantics, equal privacy protection and no worse resource use.

### Stepwise implementation review for a successor
1. Locate the current implementation and tests; record exact file paths and commit SHA before editing.
2. Trace inputs, normalization, in-memory ownership, thread transitions, durable writes, and error propagation.
3. List the existing invariants and map each to an automated regression assertion; do not duplicate functionality.
4. Implement the smallest change that fixes the verified defect or closes a roadmap gap.
5. Test success, malformed input, cancellation, concurrency, lifecycle and cleanup paths independently.
6. Measure allocations and latency on identical fixtures before asserting a performance improvement.
7. Update the cumulative handoff with the rationale, tradeoffs, exact evidence, remaining risks and next action.

### Failure injection catalogue
**FI-27-01 — Input is truncated midway through processing.** Expected: Return explicit partial/failure classification; preserve last known good user state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-27-02 — Operation is cancelled immediately before a durable write.** Expected: Do not publish success; close handles and leave a recoverable partial state. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-27-03 — Operation is cancelled immediately after a durable write.** Expected: Make retry idempotent; avoid duplicate records, files or notifications. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-27-04 — Android process or service is destroyed.** Expected: Recover from durable state without relying on in-memory flags. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-27-05 — Two operations race on the same identifier.** Expected: Enforce one owner or transactionally reject the second; no overwrite. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-27-06 — Network or document provider stops making progress.** Expected: Bound waiting and memory; report a specific error without busy spinning. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-27-07 — Device has little free storage or heap.** Expected: Fail safely before corrupting media or exhausting process memory. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

**FI-27-08 — Sensitive values appear in an exception.** Expected: Replace with numeric category; verify export redaction. Test setup: use a deterministic fixture and an injected boundary (read, write, callback, or lifecycle); record the operation ID, monotonic timestamp, state before/after and actual assertion. Do not mark PASS until the test executes.

### Review questions that require code inspection
- Where is the single authoritative owner for release and support operations, and which caller is allowed to mutate it?
- Which allocations scale with number of channels, programmes, active streams, scheduled jobs, or recording duration?
- What happens if the operation is retried after the process dies between the last two steps?
- Which messages can reach UI or support logs, and can they contain a provider credential or media URI?
- Is a claimed PASS derived from a real assertion, a mocked component, or an inferred status?
- What device/SDK/API assumptions are baked into this behavior, and which have been measured?

### Completion record template
| Field | Required entry |
|---|---|
| Work item | `TUNER-027` |
| Baseline SHA | Exact Git SHA and date |
| Files changed | Concrete repository paths |
| Root cause | Reproduction and causal mechanism |
| Alternatives rejected | Why they were less safe or more expensive |
| Unit and emulator results | Command, exit status, exact CI run |
| Physical hardware | Device model, Android version, measured result or NOT RUN |
| Memory / latency | Before and after, workload, sample count |
| Remaining risks | Open defects and clear owner |

## Cross-component consistency contracts

### One source of truth for identities
Playlist channel IDs, XMLTV IDs, saved-source IDs, recording IDs, and schedule IDs have distinct scopes. Never reuse one as another merely because values look similar. Persist explicit mappings and version migrations. If source IDs change, demonstrate guide and DVR links remain valid or show a recoverable migration path.

### Time model
Store instants as UTC epoch values; display in configured device timezone. XMLTV offsetless timestamps use the documented UTC fallback with warning. Use wall time for scheduled programme semantics and monotonic time for elapsed durations/timeouts. Recheck schedules after clock or timezone changes; do not use wall-clock differences to estimate I/O throughput.

### Cancellation model
Every network read, parser loop, storage write and scheduled job needs a clear cancellation owner. Cancellation is an outcome, not an exception to be swallowed. Partial files must be distinguishable from completed files; the catalog and filesystem must converge after restart.

### Error model
Distinguish malformed source, unsupported media, authentication, network transient, storage failure, permission denial, user cancellation and internal defect. Expose short actionable text to users; retain only numeric non-sensitive diagnostic codes. Never infer a credential issue from an unknown network exception.

### Resource model
Keep a single live playback owner by default and one active DVR writer unless capacity policy is explicitly revised. Working memory should be O(buffer + visible rows + retained guide metadata), not O(recording duration). Treat network concurrency and disk bandwidth as shared resources with explicit arbitration.

## Dependency and change-impact map

| Change | Directly affected | Regression targets |
|---|---|---|
| Channel ID normalization | playlist, guide, favorites (future), DVR | deterministic IDs, cross-source collisions, schedule mapping |
| XMLTV retention window | parser, hide-no-EPG, search, DVR future scheduling | future-only channels, timezone and cap semantics |
| Player reuse | Media3, Stream Doctor, lifecycle | rapid switches, header isolation, first frame, release |
| Recording output abstraction | raw/HLS, SAF, library, delete | bytes, path validation, unplug, partial status |
| Schedule schema | alarms, encrypted secrets, service | reboot, duplicates, transaction order, stale alarm |
| Self-test runner | startup, diagnostics, CI | privacy, no user-media mutation, no production stress |
| Guide rendering | EPG index, D-pad focus, clock | offscreen allocation, empty windows, remote navigation |

## Explicit release evidence ladder

**L0 — Documented:** Requirement or proposed design exists; no code implied.
**L1 — Source implemented:** Exact implementation reviewed, compilation not yet evidenced.
**L2 — Unit verified:** Deterministic local test passes at an exact commit.
**L3 — Emulator verified:** Built APK executes synthetic Android checks with artifact and exact run.
**L4 — Physical verified:** Real TV/phone device executes relevant scenario with logs and hardware details.
**L5 — Sustained acceptance:** Real provider and storage workloads, fault injection, battery/memory measurements, upgrade/recovery, signed release.

An L3 emulator result cannot be presented as L4 or L5. A missing exact-alarm grant remains BLOCKED even if schedule construction tests pass. A synthetic file copy cannot establish a real provider DVR recording.

## High-value autonomous acceptance suites

### Suite A — Source robustness
Generate bounded playlists and XMLTV with duplicate IDs, invalid offsets, deeply nested XML, redirects and gzip. Verify memory caps and warning fidelity. Use deterministic seeds; archive only redacted fixture identifiers.

### Suite B — UI focus and lifecycle
Use Android instrumentation or accessibility-safe test hooks to exercise D-pad navigation, guide-window shift, empty cells, search and rotation. Record focus target and visible-window boundaries; no screenshots containing credentials.

### Suite C — Playback transport
Use a local HTTP fixture for manifest and segment faults, then authorized real-provider acceptance on a dedicated test device. Observe first decoded frame, audio, buffering, stop and rapid switching.

### Suite D — DVR durability
Record to isolated scratch destination; interrupt at every state transition; verify exact bytes, finalization, catalog status and cleanup. Add real removable storage and reboot tests only with authorized external rig.

### Suite E — Memory and battery
Use 1/2 GiB-class devices, cold/warm imports, 25k-channel/250k-programme fixtures and 24-hour DVR soak. Sample PSS, native heap, Java heap, CPU and battery consistently; do not infer battery from a short synthetic test.

### Suite F — Security
Scan exported diagnostics for URL-like strings, tokens and auth headers; test XML external entities, redirect downgrade, path/URI deletion boundaries and Keystore failure. Fail build on a verified privacy regression.

## Next engineer: first 72 hours

**Hours 0–8:** Fetch main and HEAD, inspect branches, open PRs/issues and CI; compare current implementation to docs and record contradictions. Do not modify source until the baseline is reconciled.

**Hours 8–24:** Run unit tests and emulator QA on exact HEAD; capture build artifact digest, status counts and any failures. Investigate latest unverified changes first.

**Hours 24–48:** Select highest-risk unblocked defect (likely real-device DVR, scheduling, SAF or playback acceptance). Build an autonomous reproduction and fix root cause with regression coverage.

**Hours 48–72:** Benchmark memory, update one authoritative ledger, review security/privacy, merge only verified code, and write precise next recovery instructions.

## Appendix — Exact audit anchors (not code guarantees)

- `settings.gradle.kts` GitHub blob SHA `fa926d0bb05a6fc1de9ef4cd40b4c1a8de2c58dd` observed during this edition.
- `docs/FEATURE_STATUS.md` blob SHA `6082fc37a8e645b12f3fec005114c33b8708a862` observed during this edition.
- `docs/HANDOFF.md` blob SHA `8a86d503a774441701efeb90eb890c1668509806` observed during this edition.
- `docs/ON_DEVICE_QA.md` blob SHA `1e72dcfed9749218f18ce6f63253473c64ca283b` observed during this edition.
- `.github/workflows/android.yml` builds and tests on Java 17 / Gradle 8.9 / Android 35 emulator; current exact HEAD CI not independently verified here.

## Handoff integrity warning
This is an evidence-aware engineering book, not a claim that every proposed contract has been implemented. All new test cases are specifications until run. The source repository and exact CI artifacts outrank prose when they conflict.

