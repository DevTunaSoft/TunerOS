QA-20 - deterministic bounded runtime parental decision gate
============================================================

Owned paths only: scripts/qa20/**, qa/fixtures/qa20/**, app/src/androidTest/**.
No core/src, app/src/main, Gradle, workflow, shared manifest, document, PR or merge is changed.

Audit boundary (read first)
---------------------------
Catalogued QA-01 through QA-19 from their worker branches before choosing this slice:
- QA-01 compiled TV-guide layout / D-pad; QA-02 seeded EPG fixture; QA-03 playback soak.
- QA-04 schedule durability; QA-05 guide search; QA-06 large EPG layout;
  QA-07 PBKDF2 PIN *store* records and favourites filtering.
- QA-08 DVR overlap / series rules; QA-09 doctor report privacy; QA-10 event-log aggregation.
- QA-11 backup/restore; QA-12 crash/partial writes; QA-13 timeshift windows;
  QA-14 reminders; QA-15 repeated import retention; QA-16 cancellable staging.
- QA-17 SAF destination / location lifetime; QA-18 corrupt evidence envelopes;
  QA-19 source-feed acquisition.

No QA gate replays the runtime parental *decision* stream: failed-entry counting, lockout on
the fifth wrong entry, refusal of the correct entry while locked, expiry at the exact deadline,
restart-persisted failures and lock state, and the bounded group/channel/recording policy sets.
QA-07 validates the persisted store record only. QA-20 owns the adjacent runtime slice and
does not repeat the store, backup or report checks. The real pure-Kotlin ParentalControls
source is compiled into the gate; no product source is edited.

Commands (offline, JDK 17, no new dependency)
---------------------------------------------
  export JAVA_HOME=C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2
  bash scripts/qa20/run.sh generate     regenerate pinned scratch fixtures byte-for-byte
  bash scripts/qa20/run.sh verify       RED mutants, RESTORE proof, GREEN host gate, JVM mirror
  bash scripts/qa20/run.sh red-green    alias for verify
  bash scripts/qa20/run.sh gate-only    GREEN gate without mutation controls
  bash scripts/qa20/agp_compile.sh      offline AGP compile/package; no device execution

Fixture contract
----------------
qa/fixtures/qa20 holds contract.txt, dimensions.tsv, events.tsv (160 rows),
policy.tsv (80 rows), corrupt.hex (8 named variants), sha256.txt and .gitattributes.
All identifiers are invented scratch tokens. Every byte is generated from fixed constants;
verify regenerates in memory and compares bytes. No feed address, access marker, PIN,
credential, DRM key or recording payload is stored. The leak marker is derived at run time
from a fixed seed and is never written to a source or fixture file.

Pinned data digests (SHA-256):
  events.tsv   8c2c8cdec14e90ccc5bf2054e20626ae51c26ef578c7389555bfa389de21578d
  policy.tsv   9403de81e5688cd5f961a7313276b8dfa3acb281dbec3b90bd293a903d2a117b
  corrupt.hex  cfd50e7debea1c2f8825cd867ec30bd196658b30947abd88a2ecd222caa30e63
Fixture tree digest after all mutants: ca7ba5224173cc0734fb44173b00349e8b2106e298ae766378d5b8702bff7daf
Fixture size: 21,779 bytes (cap 262,144). Total generated files: 6.

Bounds actually exercised: 8 event families x 20 cases (all nonzero), 4 policy families x
20 cases (all nonzero), 30 accepted / 70 rejected / 60 locked event outcomes, 5 cap-reject
and 15 accepted bounded-set rows, 8 distinct corrupt reasons. The host JVM runs with -Xmx96m,
six replay cycles on a named worker thread joined before the heap/thread drift checks, and an
explicit 48 MiB used-heap and zero-thread-drift assertion.

RED controls
------------
Nine scratch-only mutations, each required to exit nonzero with its own pinned FIRST code:
  BYTE_IDENTITY, MANIFEST_MISMATCH, HEADER_STRICT, FIELD_COUNT, INT_BOUNDS,
  FAMILY_CODE, OUTCOME_MISMATCH, CORRUPT_REJECT, PRIVACY_SCAN.
Every mutated file is restored byte-identically from the committed fixture and checked with
cmp; the committed fixture tree hash is compared before and after the whole RED batch. A
deliberately relaxed decoder accepts every corrupt payload that the strict decoder rejects,
so the fail-closed control is not vacuous.

Evidence for the committed fixture
----------------------------------
Host gate: 26 checks, 26 PASS, 0 FAIL. JVM mirror: 7 checks, 7 PASS, 0 FAIL.
RED: 9/9 mutants PASS with pinned first codes. RESTORE: byte-identical tree digest above.
Offline AGP: compileDebugAndroidTestKotlin and assembleDebugAndroidTest PASS; test APK
37,762 bytes; instrumentation entry present in the packaged manifest.

Device execution
----------------
A compiled test APK is not a device PASS. No authorized adb/emulator target was attached,
so instrumentation execution is BLOCKED / UNVERIFIED.

Integration note
----------------
app/src/androidTest/AndroidManifest.xml here contains only the QA-20 instrumentation entry.
When sibling QA manifests are integrated, the integrator must merge the distinct entries
rather than replace one worker's file with another's.
