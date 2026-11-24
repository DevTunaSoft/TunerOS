# QA-69 — bounded scheduled-recording preparation claim/delete host gate

Base SHA: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`
Branch: `hermes/worker-c53-qa69`
Owned paths: `scripts/qa69/**`, `qa/fixtures/qa69/**`, `app/src/androidTest/**`.
No product, core, Gradle, workflow, root document, sibling QA path or `.hermes` file is edited.
No PR is opened and no merge is performed; the coordinator owns both.

## Chosen slice

The real app-side `app/src/main/java/com/devtunasoft/tuneros/RecordingScheduleStore.kt`
functions `orphanedPreparationsAfter`, `markRecoveringIfOrphaned` and `deleteRecovered`:
a bounded enumeration of unfinished preparation rows (ordered by id, exclusive `afterId`,
limit 1..128), a claim that may move only an inert reservation with the same id and scratch
source marker into RECOVERING, and a deletion that removes only a RECOVERING row with that
same marker. The host gate compiles the unmodified production file with the offline Kotlin
2.0.21 compiler plus minimal scratch Android/SQLite stand-ins and calls the compiled
production code directly. The SQL the production store emits is evaluated by a small scratch
subset engine, so a changed selection string changes observed behaviour. No production
behaviour is reimplemented in the verifier. The shared Android-free mirror compiles into both
the host gate and the instrumented source set, so both derive identical fixture bytes.

## Cross-branch audit and NO_EQUIVALENT_GATE finding

Read first: the repository `CLAUDE.md` and the shared scratch `AGENTS.md`.

- refs scanned: 457 (`git for-each-ref`, local heads and remote refs); 138 refs contain
  in-scope QA files.
- in-scope corpus: 984 `(ref, path)` entries, 936 distinct blobs, 7,740,759 deduplicated
  bytes; 386 script paths, 485 fixture paths, 113 androidTest paths.
- exclusions: `.gitattributes` blobs, duplicate git objects, paths outside
  `scripts/qa*`, `qa/fixtures/qa*` and `app/src/androidTest`, all of `docs/**`, root
  documents, `app/src/main/**`, `core/**`, Gradle files, workflows and `.hermes/**`.
  Production sources were audited read-only; only worker-c1-seriescancel changes this
  production file on any sibling branch, and that delta does not add these guards.
- zero-hit keywords in the QA corpus include: `orphanedPreparationsAfter`,
  `markRecoveringIfOrphaned`, `deleteRecovered`, `OrphanedPreparation`,
  `DvrScheduleStore`, `seriesPlan`, `GuideIndex`, `ParentalGate`, `RestrictedChannel`,
  `NotificationPlanner`, `TunerGuard`, `KeyGate`, `GuideFocus`, `Sidx`, `XmlTv`, `M3u`,
  `BodyLengthPolicy`, `RecordingStore`, `RestoreCheck`, `StartupRecovery`, `Qa69`,
  `BoundedLineReader`, `WindowedEpg`, `DvrPolicy`, `AlarmSettle`.
- adjacent gates examined: QA-04 owns durable schedule round-trip, 200-row keyset paging
  and exact-alarm rearm; QA-08 owns series-rule and reservation-overlap decisions; QA-12
  owns restart reconciliation of recording bytes against a crash catalog; QA-20 owns the
  parental attempt decision; QA-47 owns shutdown journal checks; QA-68 owns playback-failure
  cause-chain evidence. None drives this bounded claim/delete state machine.

**Finding: NO_EQUIVALENT_GATE.** QA-01..QA-68 contain no gate for this seam.

## Commands

    bash scripts/qa69/run.sh generate    regenerate committed scratch fixtures in place
    bash scripts/qa69/run.sh regen       regenerate into scratch and compare aggregate digest
    bash scripts/qa69/run.sh full        REGEN, RED mutants, RESTORE, GREEN, PRIVACY
    bash scripts/qa69/run.sh green       GREEN only
    bash scripts/qa69/mirror_check.sh    JVM run of the Android-free instrumented mirror
    bash scripts/qa69/agp_compile.sh     offline AGP compile and APK assembly; no device run
    python3 scripts/qa69/privacy_scan.py qa/fixtures/qa69 [reports...]

The host gate uses `-Xmx96m`, bounded `timeout` wrappers, no network and check ids, counts and
digests only in diagnostics. All mutation targets live under the Hermes scratch directory;
the committed production source and committed fixtures are only ever read, and every scratch
copy is restored and rehashed after each mutant.

## Fixture contract

`qa/fixtures/qa69` holds six files generated from fixed constants: `.gitattributes`,
`README.md`, `contract.txt`, `dimensions.tsv`, `cases.tsv` (48 rows in four state families)
and `sha256.txt` (data-file digests only). The page cap is 16 and 24 rows are orphaned work,
so the cap binds; the after id is `c0004` and both expected pages have 16 ids. All names are
invented scratch values; no provider address, credential, personal identification number,
protected-content key or recorded media payload is represented here.

## Pinned first-failure codes

`corrupt-relax` CORRUPT_REJECT, `prod-limit` LIMIT_BOUND, `prod-enum` ORPHAN_ENUM,
`prod-after` ORPHAN_AFTER, `prod-keyfield` ORPHAN_KEY_FIELD,
`prod-claim` CLAIM_KEY_GUARD, `prod-delete` DELETE_STATE_GUARD,
`byte-drift` BYTE_IDENTITY, `sha-drift` MANIFEST_CONSISTENT,
`digest-drift` PINNED_DIGEST, `privacy-leak` PRIVACY_SCAN.

The deliberate privacy-leak report is excluded from the final privacy scan: its purpose is to
prove the detector fires (RED), and the final verdict requires zero hits on ordinary diagnostics
and fixture bytes. Device instrumentation execution is BLOCKED/UNVERIFIED without an attached
authorized device or emulator; this gate does not claim a device PASS from a compile alone.
