QA-58 - bounded watch-history / resume-position persistence QA gate
====================================================================

Scope
-----
Host-side, zero-dependency, deterministic contract gate over a bounded synthetic
resume-position store. It verifies round-trip persistence, bounded eviction,
stable recency ranking, fail-closed corrupt input, stale-write rejection and
privacy-safe diagnostics. The same framework-free contract source is compiled
into a minimal instrumented mirror; no device execution is claimed here.

Why this slice (audit evidence)
-------------------------------
Before building, 435 refs (151 local heads, 147 unmerged) were scanned in one
batched blob pass. Two deduplicated corpora were written:
  QA scripts/fixtures/androidTest: 142 unique blobs, 743,555 bytes
  JVM unit tests (core/src/test, app/src/test): 265 blobs, 1,775,306 bytes
Full report: C:/Users/X1/AppData/Local/hermes/cache/scratch/qa58_audit/audit_report.txt

Zero-hit keywords in BOTH corpora included: resume_persistence_roundtrip,
history_corrupt_failclosed, history_pin_diagnostics, epg_query_normalization,
watch_history_androidtest. The only "watch history" hits in the QA corpus are
QA-52 player-lifecycle labels such as PAUSE_RESUME; they are not persistence.
The candidate EPG-search slice was rejected because CORE-30 already pins
query normalization, 30-point scoring and guide-ID tie-break on the real
GuideDoctor unit; QA-58 does not duplicate it.

Named exclusions: QA-11 guide-state persistence, QA-12 DVR recovery,
QA-15 repeated EPG import retention, QA-05 search, QA-06 large EPG,
QA-38..QA-57 (including QA-52 player reuse, QA-53 M3U metadata, QA-54 favorite
stable key, QA-55 XMLTV tokens, QA-56 display numbers, QA-57 recording labels).

No production watch-history or resume-position store exists at the base SHA, so
this gate pins a bounded acceptance contract rather than editing product code.
Owned paths only: scripts/qa58/**, qa/fixtures/qa58/**, app/src/androidTest/**.

Fixture and pinned digest
-------------------------
  rows.tsv         20,000 synthetic entries (fixed ids ch000000..ch019999)
  dimensions.tsv   pinned dimensions
  corrupt.txt      7 named corrupt-document cases
  report-copy.txt  clean scratch diagnostics copy
  sha256.txt       per-file manifest (data files only)
  sha256(rows.tsv) b1034f673ae16a9cf1a2737beaa86e83bb56e7f73af65a3cfc196a74784709f6

Regeneration is in memory from fixed constants; verify never writes into the
repo. The diagnostic secret is derived at run time from a fixed seed and is
never written as a literal. No provider URL, credential, PIN or recording
payload text appears in fixtures, sources or emitted diagnostics.

Commands
--------
  JAVA_HOME=<jdk17> bash scripts/qa58/run.sh verify
  bash scripts/qa58/run.sh gate-only
  bash scripts/qa58/agp_compile.sh

run.sh verify asserts: byte-identical regeneration, manifest consistency,
pinned digest, 11 negative-mutation RED controls with pinned FIRST codes,
byte-identical restoration of owned sources and fixtures, then a bounded GREEN
run (-Xmx96m, zero thread drift). RED codes: BYTE_IDENTITY,
MANIFEST_CONSISTENT, PINNED_DIGEST, HEADER_STRICT, ROW_BOUNDS,
CORRUPT_FAIL_CLOSED, PRIVACY_SCAN, CAP_BINDING, ROUND_TRIP, RANK_STABLE,
STALE_REJECT. All mutants live in scratch; production and committed fixtures
are untouched.

Integration note
----------------
app/src/androidTest/AndroidManifest.xml is shared across unmerged QA branches.
This branch supplies only the QA-58 instrumentation entry; the integrator must
merge sibling entries rather than overwrite them.
