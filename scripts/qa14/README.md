QA-14 - deterministic reminder / notification scheduling gate
=============================================================

Owned paths only: scripts/qa14/**, qa/fixtures/qa14/**, app/src/androidTest/**.
No production source, core source, Gradle file, shared manifest or document is changed.
No PR is opened and no merge is performed.

Audit boundary (read first)
---------------------------
- QA-04 owns scheduled-recording SQLite durability and exact-alarm rearm.
- QA-08 owns DVR overlap windows and series-rule expansion.
- QA-11 owns TunerBackup encode/decode and restore normalization.
- QA-12 owns DVR crash and partial-write catalog recovery.
- QA-13 owns timeshift ring and live-window seek bounds.
No audited harness covers app-level reminder rows, notification firing persistence,
DST/timezone resolution, duplicates, bounded ordering or fail-closed reminder state.
QA-14 owns that gap; it does not repeat the audited seams. There is no production
reminder subsystem at the pinned base SHA, so the gate verifies the scratch-state
contract and the compiled mirror rather than claiming a product code PASS.

Commands (JDK 17, no network, no new dependency)
------------------------------------------------
  bash scripts/qa14/run.sh generate    regenerate pinned scratch fixtures
  bash scripts/qa14/run.sh verify      15 RED mutants, GREEN host gate, byte-identity
                                       reproduction, Kotlin mirror (default)
  bash scripts/qa14/run.sh red-green   alias for the full verify path
  bash scripts/qa14/agp_compile.sh     offline AGP compile/package attempt

Fixture contract
----------------
qa/fixtures/qa14/ holds reminders.tsv (1024 bounded scratch rows), corrupt.tsv
(15 named corrupt variants as hex payloads), fired.tsv (128 delivery-ledger rows),
dimensions.tsv (pinned derived counts/digests), sha256.txt and .gitattributes.
Rows use only invented ids and category tags. No stream address, credential, PIN,
secret or recording payload is stored or printed. The leak marker is derived at
run time from a constant seed; it never appears as a literal in source or fixture.

Model and checks
----------------
Local wall-clock reminders are resolved through java.time zone rules, so a spring
gap shifts forward, a fall overlap picks the earlier offset, and a UTC fire instant
stays ordered across zones. Eligibility requires SCHEDULED state, EXACT arm and a
future fire instant. Dedupe uses (zone, local, key, lead). Output is sorted by
(fireEpoch, id) and visited in 64-row pages. A fired row needs exactly one ledger
entry. Fail-closed parsing rejects bad headers, fields, ids, zones, local times,
leads, categories, states, keys, arms, terminators, duplicate ids and encoding.

GREEN report: 21 positive checks, 10 in-memory negative controls, 31 total,
zero failures. Kotlin mirror: 12 checks with a pinned digest. RED: 15/15 mutants
rejected with their named FIRST code. Metrics come from machine-readable output.

Device status
-------------
A compiled test APK is not a device PASS. Without an attached, authorized device
or emulator, device execution is BLOCKED/UNVERIFIED.
