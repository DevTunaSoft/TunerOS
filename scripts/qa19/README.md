QA-19 - deterministic bounded source-feed acquisition gate
=========================================================

Owned paths only: scripts/qa19/**, qa/fixtures/qa19/**, app/src/androidTest/**.
No core/src, app/src/main, Gradle, workflow or shared document is changed.
No PR is opened and no merge is performed by this worker.

Audit boundary (read first)
---------------------------
- QA-01 small compiled TV-guide layout / D-pad; QA-02 seeded EPG fixture;
  QA-03 playback-soak lifecycle and taxonomy.
- QA-04 scheduled-recording durability and exact-alarm rearm; QA-05 guide search;
  QA-06 large EPG layout; QA-07 favourites and the parental PIN store.
- QA-08 DVR overlap and series rules; QA-09 doctor shareable-report privacy;
  QA-10 event-log aggregation; QA-11 backup encode/decode and restore.
- QA-12 crash / partial-write catalog recovery; QA-13 timeshift windows;
  QA-14 reminders; QA-15 repeated large-EPG import retention; QA-16 cancellable
  staging and rollback; QA-17 SAF destination and recording location;
  QA-18 corrupt evidence-envelope ingestion before doctor reports.

None of those gates reads the source-feed acquisition seam. CoreTest has unit
assertions for SourceDownloader, but no deterministic fixture, no RED mutation
suite, no pinned scratch bytes and no instrumented mirror covers it. QA-19 owns
that distinct slice: URL admission, bounded redirect chains, HTTPS-downgrade
rejection, gzip sniffing (magic bytes / header field / .gz path), the
decompressed byte cap on both read and skip paths, the zero-progress read guard,
fail-closed corrupt/truncated input, and private diagnostics.

What runs
---------
The host gate compiles the real core SourceDownloader.kt together with the
framework-free Qa19Contract.kt (the same source used by the androidTest mirror)
using the offline Kotlin 2.0.21 compiler jars. A stub HttpURLConnection supplies
bounded in-memory bodies; no socket is opened.

  bash scripts/qa19/run.sh generate     regenerate the pinned scratch fixtures
  bash scripts/qa19/run.sh verify       RED mutants, RESTORE proof, GREEN, mirror
  bash scripts/qa19/run.sh red-green    alias for verify
  bash scripts/qa19/run.sh gate-only    GREEN host gate without mutation controls
  bash scripts/qa19/agp_compile.sh      offline AGP compile/package attempt

Fixture contract
----------------
qa/fixtures/qa19/ holds contract.txt, dimensions.tsv, cases.tsv (224 rows),
corrupt.hex (8 named variants), sha256.txt and .gitattributes. Every field is a
symbolic token. Address-shaped values are built in memory from character arrays
with the RFC 2606 reserved suffix; the derived leak marker is computed from a
fixed seed and is never written to source, fixture or report.

Dimensions: seed 20261123; 14 families x 16 cases = 224; cap 256 bytes per case
line; 23,508 fixture bytes total. cases.tsv SHA-256 is pinned in Qa19Contract
and in dimensions.tsv; sha256.txt pins all four data files. Each family has a
nonzero count, and every cap (case count, line length, body/cap bounds, fixture
size, 96 MiB JVM heap, 48 MiB used-heap assertion, 0 thread drift) is exercised.

Results recorded for the committed fixture
------------------------------------------
Host gate: 31 checks, 31 PASS, 0 FAIL. JVM mirror: 7 checks, 7 PASS, 0 FAIL.
Ten on-disk mutations each exited nonzero with its pinned first code:
BYTE_IDENTITY, MANIFEST_MISMATCH, HEADER_STRICT, FIELD_COUNT, INT_BOUNDS,
FAMILY_CODE, REJECT_CAP_READ, REJECT_DOWNGRADE, CORRUPT_REJECT, PRIVACY_SCAN.
The committed fixture tree digest was identical before and after all mutants.

Device execution
----------------
androidTest sources and the instrumentation entry are compiled and packaged by
AGP when a local SDK is available. Running them needs an attached authorized
device or emulator; without one the device verdict is BLOCKED / UNVERIFIED and
is never reported as a PASS.
