QA-26 - bounded DASH MPD planning host gate
===========================================

Owned paths only: scripts/qa26/**, qa/fixtures/qa26/**, app/src/androidTest/**.
No core/src, app/src/main, Gradle, workflow, shared document or production
manifest file is changed. No PR is opened and no merge is performed by this
worker.

Slice and audit
---------------
Before choosing the slice, QA-01..QA-25 were catalogued from their worker
branches. Explicit exclusions:

- QA-01/QA-06 compiled guide layout and large EPG rows; QA-02/QA-15 EPG
  fixture import and repeated-import retention.
- QA-03 playback soak; QA-04 schedule rearm; QA-05 guide search;
  QA-07 favourites and PIN store; QA-08 DVR overlap and series rules.
- QA-09 doctor report privacy; QA-10 event log; QA-11 backup/restore;
  QA-12 crash recovery; QA-13 timeshift; QA-14 reminders.
- QA-16 cancellable staging; QA-17 SAF destination; QA-18 corrupt evidence
  envelopes; QA-19 source acquisition; QA-20 parental decisions.
- QA-21 report publication; QA-22 HLS AES-128 IV admission; QA-23 DASH sidx
  index admission only; QA-24 title/season-episode normalisation;
  QA-25 recording-catalog paging.

No audited gate reads the DASH MPD planning seam. QA-23 compiles the sidx
parser but never calls DashManifest.plan. QA-26 compiles the real pure-Kotlin
DashManifest (plus its pure-Kotlin dependencies) read-only and drives its
static/live planning, bandwidth choice, BaseURL handling, SegmentTemplate
expansion, SegmentList, SegmentBase and SegmentTimeline decisions from a
byte-pinned scratch fixture. No production file is edited.

Commands (JDK 17; offline Kotlin 2.0.21 jars from the local Gradle cache)
-------------------------------------------------------------------------
  bash scripts/qa26/run.sh generate     regenerate pinned scratch fixtures
  bash scripts/qa26/run.sh verify       15 RED controls, RESTORE, GREEN, privacy
  bash scripts/qa26/run.sh red-green    alias for verify
  bash scripts/qa26/run.sh gate-only    GREEN gate and privacy without mutants
  bash scripts/qa26/mirror_check.sh     JVM run of the Android-free mirror
  bash scripts/qa26/agp_compile.sh      offline AGP compile/package attempt
  bash scripts/qa26/device_gate.sh      truthful device status probe

Fixture contract
----------------
qa/fixtures/qa26 holds contract.txt, cases.tsv (89 bounded rows), corrupt.tsv
(23 named fail-closed variants), dimensions.tsv and sha256.txt. Every byte is
regenerated from fixed constants. Tables have exact tab-separated headers;
dimensions use tab-separated pairs. All values are invented qa26 tokens;
standard XML namespaces are the only scheme-like text. No provider address,
access marker, personal code, DRM key or recording payload is stored. The
sensitive marker used by the privacy control is derived from the pinned seed
at run time and never written to source, fixture or report.

Green checks
------------
BYTE_IDENTITY, MANIFEST_MATCH, DIMENSIONS, HEADER_STRICT, FIELD_BOUNDS,
CASE_COVERAGE, PLAN_FINGERPRINT, NEEDS_INDEX, TEMPLATE_EXPAND, CORRUPT_REJECT,
LAX_ACCEPT, CAP_BINDING, PRIVACY_FIXTURE, PRIVACY_REPORT and
HEAP_THREADS_BOUNDED. The heap check runs six bounded plan cycles on a named
worker thread under -Xmx96m, samples inside and after the worker, and asserts
bounded drift, thread count and no leaked qa26- threads.

Negative controls
-----------------
Nine fixture mutants and six source mutants, each required to exit nonzero
with its own pinned FIRST code. Mutant manifests are rewritten where needed so
digest drift cannot mask the named semantic rejection. Scratch sources are
restored from their originals and compared byte-for-byte; the production and
fixture tree digests are compared before and after the whole run.

Instrumentation and device status
---------------------------------
app/src/androidTest declares only QA-26's runner. Sibling workers own sibling
entries; the integrator merges the distinct entries when integrating.
Qa26Contract.kt has no Android imports, runs on the JVM and inside the test
APK. Qa26Instrumentation only generates scratch data, invokes the mirror,
writes a bounded report and returns a result bundle.

A compiled test APK is not a device PASS. Without an attached, authorized
adb/emulator target, on-device execution is BLOCKED / NOT RUN / UNVERIFIED.
No device result is claimed here.
