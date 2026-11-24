# QA-65 — bounded canonical series-rule title normalization host gate

## Chosen slice

Pure-Kotlin `core/SeriesRules.kt` canonical title normalization and rule-id admission:
`titleKeyOf`, `ruleFor`, `titleMatches` (via `matches`) and the `SeriesRule.id` contract.
The fixture exercises ASCII and Latin-1 uppercase folding, leading-separator
suppression, non-alphanumeric-run collapse to one interior space, null-on-empty
normalization, normalization idempotence, empty-channel-to-`*` rule ids, and the
delimiter-freedom of the normalized key.

## Cross-branch audit and NO_EQUIVALENT_GATE finding

Read in full before coding: `C:/Users/X1/TunerOS/CLAUDE.md` and the scratch
`AGENTS.md`. Then audited all local heads and `origin` branches.

- refs scanned: 448 (158 local heads + 291 remote refs, deduplicated)
- refs containing in-scope QA files: 132
- distinct QA blobs read: 889; QA corpus: 7,570,832 bytes
- distinct JVM-test blobs read: 266; JVM-test corpus: 1,775,335 bytes
- total corpus: 9,346,167 bytes
- distinct in-scope paths: 933 (465 fixture, 363 script, 105 androidTest)
- blobs excluded as oversize (>2 MiB): 0
- QA task numbers observed in the corpus: 1..64
- zero-hit keywords in QA corpus (23): wallclock, checksum, crc32, bookmark,
  retry, backoff, sliding, coalesce, drain, throttle, debounce, round-robin,
  quorum, fence, token-bucket, rate-limit, lexicographic, collation, grapheme,
  underflow, saturating, bitfield, varint
- zero-hit keywords in JVM-test corpus (25): dedupe, checksum, crc32,
  byte-identity, checkpoint, resume, bookmark, seek, coalesce, throttle,
  debounce, round-robin, quorum, fence, token-bucket, rate-limit, lexicographic,
  collation, grapheme, underflow, saturating, bitfield, varint, thread-drift
- zero-hit in both corpora (18): checksum, crc32, bookmark, coalesce, throttle,
  debounce, round-robin, quorum, fence, token-bucket, rate-limit, lexicographic,
  collation, grapheme, underflow, saturating, bitfield, varint

Function-level scan of every `fun` declaration in `core/src/main/**/*.kt` against
both corpora produced zero QA-gate references for `titleKeyOf`, `ruleFor`,
`titleMatches` and `SeriesRules.plan`. The only `SeriesRules` tokens anywhere in
the QA corpus are `SeriesRules.MAX_RULES` bound checks in the QA-38 backup gate
and one README note about reading the file. Sibling gates own adjacent but
distinct slices: QA-08 DVR series-rule expansion, QA-37 series-rule replacement,
QA-38 backup rule refusal, QA-24 episode/season normalization. None calls the
core normalization seam. Base-tree JVM tests (`SeriesRulesTest`,
`SeriesPlanPropertyTest`) cover ASCII normalization and planner oracles; they are
unit tests, not QA gates, and do not cover the byte-exact fixture, pinned-digest,
Unicode Latin-1 case family, negative-mutation or instrumented-mirror dimensions.

**Finding: NO_EQUIVALENT_GATE.**

Exclusions: `docs/**`, README/AGENTS/CLAUDE text, other `scripts/qa*` and
`qa/fixtures/qa*`, `app/src/main/**`, `core/**`, Gradle files, root manifest,
workflows and `.hermes/**` were read for context or audited read-only and were
never modified. The gate compiles the unmodified production sources into its own
classpath; all mutations are applied to scratch copies.

## Files

- `scripts/qa65/Qa65Gate.java` — zero-dependency Java gate (`generate` / `verify`)
- `scripts/qa65/mutate.py` — scratch-only single-needle mutation helper
- `scripts/qa65/run_qa65.sh` — bounded runner (RED → RESTORE → GREEN)
- `qa/fixtures/qa65/` — dimensions.tsv, cases.tsv, sha256.txt (LF, `* -text`)
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa65/` — Android-free
  assertions mirror and bounded `Instrumentation` runner
- `app/src/androidTest/AndroidManifest.xml` — single QA-65 instrumentation entry

## Bounded fixture

2543 bytes total: dimensions 140, cases 2248, manifest 155. 64 cases
(8 null, 10 leading, 12 spacing, 12 case, 6 idempotence, 8 rule-empty-channel,
8 rule-with-channel) plus a header. Maximum title length 128 characters.
Generation is deterministic from seed 20261124; `verify` regenerates in memory
and compares all three files byte-for-byte. Pinned SHA-256 for cases.tsv:
`539c244b1f89f99823a414438fe96942f4dafa426d1a09e649ab56a852cee9c3`.
The marker secret is derived at run time from the seed and is never written to
source, fixture or report.

## Negative controls (all must go RED with a pinned FIRST code)

| mutant | scratch mutation | pinned FIRST |
|---|---|---|
| M1_CASE | drop `lowercaseChar()` | CANON_CASE |
| M2_LEAD | emit leading separator space | CANON_LEAD |
| M3_SPACE | suppress interior separator space | CANON_SPACE |
| M4_NULL | return empty key instead of null | CANON_NULL |
| M5_CHANNEL | empty-channel rule id loses `*` | RULE_CHANNEL |
| M6_ID | change rule-id delimiter | RULE_ID |
| F7_MANIFEST | byte flip in scratch manifest | BYTE_IDENTITY |

## Privacy

The gate scans all fixture bytes and every emitted diagnostic line for URL
schemes, provider-feed tokens, credential/user/token/secret keys, PIN markers,
recording/payload text and the derived marker; zero hits are required. Only
check ids, counts and digests are printed; no title, source or payload bytes are
echoed. Scratch names only; no real media or database was touched.

## Executed evidence (host SHA a523c2e43d198ee752f7c4f03f9f7853356ffb30 at time of build)

- RED: 7/7 mutants reached their pinned FIRST code and exited nonzero.
- RESTORE: repository production and fixture bytes unchanged; scratch copies
  byte-identical after restore.
- GREEN: 79/79 checks PASS under `-Xmx96m` (max heap 100,663,296 bytes),
  zero thread drift, regeneration byte-identical.
- Instrumented mirror on JVM: 9/9 PASS, both pinned generator digests matched.
- Offline AGP `:app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest`:
  BUILD SUCCESSFUL; APK `app-debug-androidTest.apk` 15,050 bytes; instrumentation
  entry `com.devtunasoft.tuneros.qa65.Qa65Runner` present in the binary manifest.

## Device status

`adb devices` on the discovered SDK reports no attached device. Device execution
is BLOCKED / UNVERIFIED without an attached authorized emulator or adb device.
The host gate and instrumented APK compile are the executed evidence; no device
PASS is claimed.
