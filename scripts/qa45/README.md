# QA-45 — process-restart source recovery and stale import admission

Owned paths: `scripts/qa45/**`, `qa/fixtures/qa45/**`, `app/src/androidTest/**`.

## Slice

The host gate exercises `core/SourceRecoveryPolicy.kt`:

- `shouldRestore`: a saved source is restored only when it is present, matches the
  displayed source, no data is loaded and no import is active.
- `mayCommit`: a validated import result is committed only at the newest request epoch.

Audit of QA-01..QA-44 branches found zero QA-gate references to this object.
QA-19 owns feed acquisition (redirect/gzip/byte cap), QA-20 parental decisions,
QA-21 self-test report publication, QA-43 source hygiene and QA-44 the timeshift
ring filesystem seam. Core JVM unit tests exist; no bounded deterministic fixture
gate did. No production file was edited.

## Fixture

`qa/fixtures/qa45/` is generated from constants by `Qa45Gate generate`:

- 256 restore decisions, 128 epoch-commit decisions, 8 corrupt records;
- source identifiers are invented `S00000`-style tokens, never provider data;
- `sha256.txt` pins the per-file digests and `PINNED_DATA_DIGEST` in the gate
  pins the aggregate digest `99f5489d43e3b90a5daf7db372afda304b60dc04718a9bded05715450aee7c65`;
- `.gitattributes` pins LF bytes; `verify` regenerates in memory and requires
  byte identity with the committed files.

## Commands

From the worktree root:

    bash scripts/qa45/run.sh
    bash scripts/qa45/agp_compile.sh

`run.sh` compiles the real Kotlin object with the offline K2 compiler, compiles
the zero-dependency Java gate, runs five RED mutants with pinned FIRST codes,
verifies production and fixture bytes are unchanged, then runs the GREEN gate
under `-Xmx64m` and scans diagnostics plus fixture bytes for URL, credential,
secret, PIN and synthetic recording-payload text.

## Negative controls

| Mutant | Pinned FIRST |
|---|---|
| inverted production loaded-data guard | `RESTORE_TRUTH_TABLE` |
| flipped commit expectation | `COMMIT_EPOCH` |
| corrupt row made parseable | `CORRUPT_REJECT` |
| byte drift in fixture prose | `BYTE_IDENTITY` |
| synthetic diagnostic leak | `PRIVACY_SCAN` |

## Device status

No instrumentation was executed. The instrumented mirror is compiled by the
offline AGP command only when the SDK is present; device execution is BLOCKED
without an authorized adb target and UNVERIFIED otherwise. No device PASS is
claimed.
