QA-17 - deterministic SAF destination and recording-location gate
=================================================================

Owned paths only: scripts/qa17/**, qa/fixtures/qa17/**, app/src/androidTest/**.
No product, core, Gradle, workflow, shared document or production manifest file is changed.
No PR is opened and no merge is performed by this worker.

Audit boundary (read first)
---------------------------
- QA-02/QA-06/QA-15 cover EPG fixture structure, large guide layout and repeated large import.
- QA-04/QA-08/QA-12 cover schedule rearm, DVR overlap/series expansion and crash recovery.
- QA-11 covers backup/restore; QA-13 timeshift windows; QA-14 reminders; QA-16 cancellable staging.
- The shared device smoke script has a live SAF recording scenario, but no deterministic host gate
  covers destination selection, volume/capacity gating, persisted tree containment, internal-root
  confinement, document-state fail-closed mapping or bounded heap/thread use for those decisions.
QA-17 owns that slice. It verifies a scratch-state contract mirroring the app seams; it does not
claim a product-code PASS and it does not execute on a device.

Commands (JDK 17, no network, no new dependency)
------------------------------------------------
  bash scripts/qa17/run.sh generate    regenerate the pinned scratch fixture byte-for-byte
  bash scripts/qa17/run.sh verify      RED controls, byte-identical restore, GREEN gate, JVM mirror
  bash scripts/qa17/run.sh red-green   alias for verify
  bash scripts/qa17/run.sh gate-only   canonical GREEN gate without mutant controls
  bash scripts/qa17/agp_compile.sh     offline AGP compile/package attempt when an SDK is present

The host gate is one Java 17 source compiled with javac. It writes and reads only scratch
directories and the committed fixture directory. RED controls mutate scratch copies of the fixture
and of the gate source; the committed files are never edited. A before/after digest proves
byte-identical restore. Failures print check ids, counts and digests only, never raw input.

Fixture contract
----------------
qa/fixtures/qa17/ holds contract.txt, dimensions.tsv, prefs.tsv (64 stored destination values),
volumes.tsv (6 synthetic volumes including negative usable space and non-directory flags),
grants.tsv (13 persisted access grants), locations.tsv (96 play/delete cases), corrupt.tsv
(12 hex-encoded fail-closed payloads), sha256.txt and .gitattributes. Tables have exact headers;
all identifiers are invented scratch tokens derived from a fixed seed at run time. No external feed
address, access marker, personal code, key or media payload is stored. The leak detector scans
both the fixture tables and every rendered diagnostic before output.

Checks include byte-identical regeneration, pinned SHA-256, strict UTF-8/header/field parsing,
truncation detection, derived-dimension agreement, destination decision parity, tree-containment
boundary (a sibling prefix is rejected), internal-root confinement, read/write grant checks,
document-state mapping, corrupt-input fail-closed with no delete effect, bounded heap (48 MiB,
under -Xmx64m) and threads (<= 4), and privacy scans. RED controls pin 10 fixture mutants and
6 source mutants to specific first-failure codes.

Device execution
----------------
No on-device instrumentation run is performed here. Without an authorized emulator/adb target the
device verdict is BLOCKED; with one present but not exercised it is NOT RUN and on-device results
remain UNVERIFIED. Compiling or packaging the test APK is not device evidence.
