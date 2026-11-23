QA-25 — bounded DVR recording-catalog paging gate
==================================================

Owned paths only: scripts/qa25/**, qa/fixtures/qa25/**, app/src/androidTest/**.
No production, core, Gradle, workflow, manifest (app/src/main), README or shared
document file is changed.

Slice and exclusions
--------------------
This gate owns the recording-catalog listing contract that QA-01..QA-24 do not
exercise: a bounded `recent(limit)` style read over an immutable recording id,
ordered by started epoch with offset paging, preserving the protection flag and
all ten row fields, with fail-closed rejection of malformed snapshot rows.

Excluded, because each already has a gate on a sibling branch:
QA-01 compiled guide layout and remote keys; QA-02/QA-06 large EPG scale;
QA-03 playback soak; QA-04 schedule rearm; QA-05 guide search; QA-07 favorites;
QA-08 DVR overlap and series rules; QA-09 diagnostics; QA-10 event log;
QA-11 backup/restore including guide mappings; QA-12 crash/partial-write
recovery (snapshot recovery, not paging); QA-13 timeshift; QA-14 reminders;
QA-15 import retention; QA-16 import staging; QA-17 SAF destination and
location lifetime; QA-18 corrupt evidence ingestion; QA-19 source acquisition;
QA-20 parental decisions; QA-21 report publication; QA-22 HLS admission;
QA-23 DASH sidx admission. QA-24 has no branch content at the pinned root.

Fixture
-------
qa/fixtures/qa25/ holds catalog.tsv (240 synthetic rows, ten columns),
pages.tsv (8 bounded queries including the 500 cap), corrupt.tsv (8 named
malformed rows), dimensions.tsv, contract.txt and manifest.sha256. Every byte is
regenerated from fixed constants by Qa25Gate.generate. All identifiers are
invented tokens; no feed address, access marker, personal code, media content
or real path is stored. .gitattributes pins LF bytes.

Commands (from repository root)
-------------------------------
  bash scripts/qa25/run.sh generate     regenerate committed fixture bytes
  bash scripts/qa25/run.sh verify       host gate on committed bytes, -Xmx96m
  bash scripts/qa25/run.sh red-green    8 RED mutants, RESTORE, then GREEN
  bash scripts/qa25/run.sh restore      standalone byte-identity restore check
  bash scripts/qa25/mirror_check.sh     K2 host run of the Android-free mirror
  bash scripts/qa25/agp_compile.sh      offline AGP compile + test APK assemble
  bash scripts/qa25/device_gate.sh      reports device status truthfully

The negative controls are on-disk fixture mutations. Each must exit nonzero and
emit its pinned FIRST code: BYTE_IDENTITY, HEADER_STRICT, ROW_BOUNDS,
PAGE_LIMIT_BOUNDS, ID_IMMUTABLE, PROTECTION_PRESERVED, RECENT_ORDER,
PRIVACY_SCAN. Semantic mutants are remanifested and run with byte-identity
checks suppressed so the named guard, not a digest mismatch, fires first.
The byte-flip mutant runs the full mode so byte identity still has a control.
Every mutant runs in a separate scratch directory; RESTORE re-copies the
committed fixture and compares an aggregate SHA-256.

Heap and thread checks parse the catalog six times under -Xmx96m, sample inside
a named worker and after join, and assert bounded drift plus no leaked QA25-
named threads. The privacy detector derives its synthetic marker from the pinned
seed at run time and scans fixture bytes and the captured report; the independent
Python scanner repeats that check in a second language.

The instrumented APK is compiled but device execution needs an attached,
authorized device or emulator. Without one the device status is BLOCKED and
UNVERIFIED; no device PASS is claimed here.
