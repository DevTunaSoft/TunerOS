# QA-71 — bounded recording-catalog admission and recent-window gate

Owned paths: `scripts/qa71/**`, `qa/fixtures/qa71/**`, `app/src/androidTest/**`.
No product, Gradle, documentation, workflow or shared QA file was edited.

## Slice and non-duplication

The gate drives the real compiled `app/src/main/java/com/devtunasoft/tuneros/RecordingCatalog.kt`:

- `tryInsertStart`: a repeated recording identity is refused and cannot overwrite the
  protected flag or a terminal state (`CONFLICT_IGNORE`);
- `recent(limit)`: the range guard rejects 0 and 501, and the emitted
  `COALESCE(started_epoch,0) DESC` order with the emitted `LIMIT` yields the pinned window;
- `markInterruptedRecordings`: only `RECORDING` rows become `PARTIAL`, and a second call is a no-op;
- `setProtected`, `finish`: bounded updates through the emitted `id=?` selection;
- schema creation produces the state/start indexes and the protection column.

Named exclusions: QA-67 wall-clock program dates, QA-68 playback-evidence cause chains,
QA-69 the orphaned-preparation claim/delete state machine in `RecordingScheduleStore`, and
QA-70 the capture-source index-range and key-response admission. QA-29 covers DVR final-state
transitions, QA-41 the recording-outcome matrix and QA-57 library-label composition; none of
them calls this catalog store or its SQL text.

## Cross-branch audit and NO_EQUIVALENT_GATE reading

Audit performed before the slice was named:

- refs scanned: 460 local heads and `origin` refs;
- QA paths: `scripts/qa*`, `qa/fixtures/qa*`, `app/src/androidTest/**`;
- distinct QA blobs: 984; distinct paths: 1,034; corpus bytes: 7,910,578;
- sibling JVM-test blobs: 266; corpus bytes: 1,775,600;
- zero-hit keywords in both corpora: `RecordingCatalog`, `tryInsertStart`,
  `markInterruptedRecordings`, `recordings_state_idx`, `recording_catalog`,
  `CountingOutputStream`; zero-hit in the QA corpus also included `PlaylistImport` and
  `PrologReader`;
- heartbeat slice index and every QA README title were checked against the candidate list.

Finding: NO_EQUIVALENT_GATE. No prior gate or sibling JVM test names this store or any of
its methods, so the slice is genuinely uncovered.

## Fixture and checks

`qa/fixtures/qa71` holds 24 operations over 12 identities, an independent expected file, six
malformed controls, a strict `hash<TAB>filename` manifest and LF-pinning `.gitattributes`.
The generator and strict parsers live in the Android-free `Qa71Contract.kt`, so host and
instrumented mirror derive the same bytes. Pinned case-file digest:
`6184cb0fb70a8607f337fb6ac3608b7a034ad2afb50d3e31f15721d735e9101c`.

Gate modes are disjoint: `verify` runs 15 checks; `verify-manifest` skips only byte identity;
`verify-pinned` skips byte identity and manifest consistency; `verify-semantic` skips all
three structural checks. The unchanged GREEN run uses `-Xmx96m` and asserts zero thread drift.

Negative controls and their pinned first-failure codes:

| Mutant | First code |
|---|---|
| range guard relaxed | `INVALID_LIMIT_REJECT` |
| conflict policy changed to replace | `INSERT_ADMISSION` |
| order direction reversed | `RECENT_ORDER` |
| interrupted-state selection changed | `INTERRUPT_SCOPE` |
| manifest byte flipped | `MANIFEST_CONSISTENT` |
| case digest drift with fresh manifest | `PINNED_DIGEST` |
| expected-value drift with fresh manifest | `FIXTURE_ORACLE` |

Every mutant runs from a scratch copy; the runner recopies scratch production and fixtures,
then verifies their digests and the owned-tree digest are byte-identical to the pre-mutation
state. Diagnostics carry check ids, counts and hashes only. The privacy detector scans all
owned files plus the emitted report for address, credential, personal-code, protected-media
and private-media text, whitelists the standard Android XML namespace, and proves its derived
scratch marker detector with a scratch-only positive control.

## Execution and device limit

`bash scripts/qa71/run.sh full` performs regeneration, RED, RESTORE, GREEN, privacy, the JVM
mirror, offline AGP compile/assembly and an honest device probe. Instrumentation sources are in
the unique `com.devtunasoft.tuneros.qa71` package with a root manifest that carries only the
QA-71 entry; if another worker branch adds its own root instrumentation manifest, the integrator
owns that merge decision. Device execution is never claimed without an authorized target.
