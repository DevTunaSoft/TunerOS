# QA-68 — bounded playback-failure evidence extraction host gate

Base SHA: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`
Branch: `hermes/worker-c52-qa68`
Owned paths: `scripts/qa68/**`, `qa/fixtures/qa68/**`, `app/src/androidTest/**`.
No product, core, Gradle, workflow, shared manifest, document, sibling QA path or
`.hermes` file is edited. No PR is opened and no merge is performed.

## Chosen slice

The real app-side `app/src/main/java/com/devtunasoft/tuneros/PlaybackEvidence.kt`
function `evidenceOf`, together with `FirstResponseRecorder`: a bounded cause-chain
walk (first HTTP status wins, first media type wins, fixed transport labels only,
eight-cause depth cap, first-response content type preferred over exception content
type, no exception message, URL or header value retained). The host gate compiles
the unmodified production file with the offline Kotlin 2.0.21 compiler plus minimal
scratch Media3 stand-ins and calls the compiled production code directly. No
production behaviour is copied into the verifier; a separate literal rule validates
the fixture's own expected fields.

## Cross-branch audit and NO_EQUIVALENT_GATE finding

Read first: the repository `CLAUDE.md` and the shared scratch `AGENTS.md`.

- refs scanned: 455 local heads plus remote refs (deduplicated); 138 refs contain
  in-scope QA files.
- QA corpus: 2,132 `(ref, path)` entries, 974 distinct paths, 927 distinct blobs,
  7,716,792 deduplicated bytes (192,128 newline separators), corpus SHA-256
  `4d6540f2d1b37056d5f8496930285a5b42ad821b9efb42e1beb3b6042913d811`.
- base JVM unit-test corpus: 241 distinct blobs, 1,750,050 bytes, 38,637 lines.
- exclusions: `.gitattributes` (304 entries / 31 distinct blobs), duplicate git
  blobs, and paths outside `scripts/qa*`, `qa/fixtures/qa*` and
  `app/src/androidTest`. `docs/**`, root documents, `app/src/main/**`, `core/**`,
  Gradle files, workflows, the root manifest and `.hermes/**` were read for context
  or audited read-only and never modified.
- zero-hit keywords in both QA and JVM corpora (case-insensitive): `qa68`, `QA-68`,
  `evidenceOf`, `MAX_CAUSE_DEPTH`, `InvalidResponseCodeException`,
  `InvalidContentTypeException`, `first-response`, `cause-chain`, `first-wins`,
  `unescape`, `readDeclaration`, `skipComment`. `FirstResponseRecorder` appears once
  in a JVM-test corpus string and never in a QA gate.
- adjacent gates examined: QA-09 owns shareable StreamDoctor/GuideDoctor reports;
  QA-43 owns static source-level privacy scans; QA-53 owns playlist header
  interning; QA-59/QA-61 own DASH capture and timeline units; QA-63 owns doctor
  name ranking; QA-66 owns channel visibility; QA-67 owns wall-clock program-date
  rendering. None drives the app-level evidence extractor or its bounded cause
  chain.

**Finding: NO_EQUIVALENT_GATE.** QA-01..QA-67 contain no gate for this seam.

## Commands

    bash scripts/qa68/run.sh generate   regenerate committed scratch fixtures
    bash scripts/qa68/run.sh full       RED mutants, RESTORE, byte-exact REGEN, GREEN, PRIVACY
    bash scripts/qa68/mirror_check.sh   JVM run of the Android-free instrumented mirror
    bash scripts/qa68/agp_compile.sh    offline AGP compile and APK assembly; no device run
    python3 scripts/qa68/privacy_scan.py .

The host gate uses `-Xmx96m`, bounded timeouts, no network and check ids only in
diagnostics. All mutation targets live under the Hermes scratch directory; the
committed production source is only ever read. Device instrumentation execution is
BLOCKED/UNVERIFIED without an attached authorized device or emulator.

## Fixture contract

`qa/fixtures/qa68` holds `.gitattributes`, `README.md`, `contract.txt`,
`dimensions.tsv`, `cases.tsv` (64 rows in 8 families x 8 cases), `corrupt.tsv`
(six named malformed rows) and `sha256.txt` (data-file digests only). Every byte
comes from fixed constants; `verify` regenerates in memory and compares bytes, and
never writes into the fixture directory. All names and identifiers are invented
scratch values; no provider address, credential, personal identification number,
protected-content key or recorded media payload is represented here.

## Pinned first-failure codes

`prod-status` EVIDENCE_STATUS, `prod-type` EVIDENCE_CONTENT_TYPE,
`prod-transport` EVIDENCE_TRANSPORT, `prod-precedence` EVIDENCE_CONTENT_TYPE,
`prod-depth` EVIDENCE_STATUS, `byte-drift` BYTE_IDENTITY,
`sha-drift` MANIFEST_CONSISTENT, `digest-drift` PINNED_DIGEST,
`corrupt-relax` CORRUPT_REJECT, `privacy-leak` PRIVACY_SCAN.
