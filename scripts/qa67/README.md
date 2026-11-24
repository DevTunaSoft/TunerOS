# QA-67 — bounded HLS program-date wall-clock host gate

Base SHA: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`
Branch: `hermes/worker-c51-qa67`
Owned paths: `scripts/qa67/**`, `qa/fixtures/qa67/**`, `app/src/androidTest/**`.
No production, Gradle, workflow, shared document, sibling QA path or `.hermes` file is changed.
No PR is opened and no merge is performed by this worker.

## Chosen slice

The pure-Kotlin `core/TimeshiftWindow.kt` program-date seam: exact UTC ISO-8601
millisecond rendering by `programDate`, placement of `#EXT-X-PROGRAM-DATE-TIME`
immediately before `#EXTINF` in `playlist()`, omission when a segment has no
program date, and stable tag order across the retained window. The host gate
compiles the unmodified production file read-only and calls the compiled code;
no behaviour is copied into the verifier.

## Cross-branch audit and NO_EQUIVALENT_GATE finding

Read in full first: `C:/Users/X1/TunerOS/CLAUDE.md` and the scratch `AGENTS.md`.

- refs scanned: 453 (local heads plus `refs/remotes/origin`, deduplicated)
- QA refs carrying in-scope files: 130
- distinct gate blobs: 904; deduplicated QA corpus: 7,608,869 bytes
- raw corpus across all QA refs: 14,204,793 bytes / 361,574 lines
- distinct in-scope paths: 953 (377 script, 473 fixture, 103 androidTest)
- base JVM test corpus: 54 files / 328,266 bytes
- corpus digest: `fdf9e7ecb2d630cdd3793f6bdc923ec238aa4c04bcca245ea6a018ee9ab32a01`
- exclusions: `.gitattributes`, duplicate blobs, paths outside
  `scripts/qa*`, `qa/fixtures/qa*` and `app/src/androidTest`; `docs/**`,
  root docs, `app/src/main/**`, `core/**`, Gradle, root manifest, workflows
  and `.hermes/**` were audited read-only and never modified.
- zero hits in both QA and JVM-test corpora: `qa67`, `wall-clock-start`,
  `utc-carry`, `millisecond-width`, `epoch-boundary`, `program-date-tag`,
  `date-tag-placement`, `null-omits-tag`. `programDate` has zero hits in the
  whole QA corpus and three hits in one base JVM test.
- adjacent gates excluded: QA-13 (app-level window state), QA-42 (combined
  soak), QA-44 (atomic playlist publication), QA-62 (segment-file lifecycle;
  its mirror formats its own date text and never calls the production
  function), QA-63 (doctor name ranking), QA-64/QA-61 (timeline units and
  focus) and QA-02 (large EPG counts). None checks exact production wall-clock
  rendering or tag placement at epoch boundaries.

**Finding: NO_EQUIVALENT_GATE.** The base JVM test asserts one positive epoch;
there is no bounded fixture, pinned bytes, negative-mutation control or
instrumented mirror for this seam.

## Commands

    bash scripts/qa67/run.sh generate    regenerate committed scratch fixtures
    bash scripts/qa67/run.sh verify      regeneration, 13 RED controls, RESTORE proof, GREEN
    bash scripts/qa67/run.sh gate-only   GREEN gate without mutation controls
    bash scripts/qa67/agp_compile.sh     offline AGP compile and APK assembly; no device run
    python3 scripts/qa67/privacy_scan.py selftest

The host JVM runs with `-Xmx96m`, bounded timeouts, no network and no fixture
content in diagnostics. All scratch paths live under the Hermes scratch dir.

## Fixture contract

`qa/fixtures/qa67` holds `.gitattributes`, `dimensions.tsv`, `cases.tsv` (48
rows in 8 families x 6 cases), `corrupt.hex` (six named malformed rows), and
`sha256.txt`. Every byte comes from fixed constants; regeneration in a scratch
directory is byte-identical. Identifiers are invented scratch tokens. No feed
address, access marker, personal identification number, protected-content key
material or recorded media bytes is stored. The run-time marker is derived from a constant seed and is
never written to source, fixture or a passing report.

Pinned data digests (SHA-256):

- `cases.tsv` `322f03b28b3c17181d488223937e22cca912eb69ed92e16be13afe7fab665cad`
- `corrupt.hex` `4733a9b1d242498b1e801a8f2dc9aa8397aa0060e925607694a9356d468ac9fe`

Bounds exercised: 48 cases, 8 families, 160 character rows, 3 retained
segments, 6 cycles, a 0-thread-drift bound and a 48 MiB used-heap bound under a
96 MiB heap. Every pinned cap binds.

## Checks and negative controls

Sixteen positive checks: `HEADER_STRICT`, `MANIFEST_CONSISTENT`,
`BYTE_IDENTITY`, `PINNED_DIGEST`, `ROW_BOUNDS`, `CAP_BINDING`,
`FAMILY_COVERAGE`, `CORRUPT_FAIL_CLOSED`, `PRIVACY_FIXTURE`, `DATE_EXACT`,
`PLAYLIST_TAG`, `NULL_OMITS`, `ORDER_TAG`, `THREAD_STABLE`, `MEM_STABLE`,
`PRIVACY_REPORT`.

Thirteen scratch-only mutations each exit nonzero with a pinned first code:
eight fixture mutants (`BYTE_IDENTITY`, `MANIFEST_CONSISTENT`,
`PINNED_DIGEST`, `HEADER_STRICT`, `ROW_BOUNDS`, `CAP_BINDING`,
`CORRUPT_FAIL_CLOSED`, `DATE_EXACT`), four compiled production mutants
(`DATE_EXACT` twice, `NULL_OMITS`, `ORDER_TAG`) and one compiled gate mutant
(`PRIVACY_REPORT`). Each mutant family uses a verifier mode that skips only the
structural checks capable of masking its named code. Production and fixture
hashes are compared before and after the whole batch.

## On-device mirror

`app/src/androidTest/java/com/devtunasoft/tuneros/qa67/` holds the
Android-free shared contract and a bounded instrumentation runner. The JVM
mirror run reports 7 checks, all passing, with the same pinned data digests.
The root androidTest manifest is shared with sibling workers; this branch adds
only its own instrumentation entry and treats any merge of sibling entries as
an integrator decision. No authorized device or emulator target is attached;
instrumented execution is BLOCKED / NOT RUN and physical-device acceptance is
UNVERIFIED.
