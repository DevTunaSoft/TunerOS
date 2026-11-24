QA-42 - deterministic offline bounded playback/EPG soak gate
============================================================

Owned paths only: scripts/qa42/**, qa/fixtures/playback-soak42/**, and new
tests under app/src/test/java/com/devtunasoft/tuneros/qa42/**. No product
source, Gradle file, manifest, shared script, other QA path, document or
.hermes state is changed. No PR is opened and no merge is performed.

Audit (read-only, not duplicated)
---------------------------------
- QA-03 (hermes/worker-b2-qa03) is a 128-step channel-switch soak with player
  reuse, loopback transport, thread and heap checks. It has no synthetic EPG
  window, no page recycling and no timeshift retention model.
- QA-06 (hermes/worker-b15-qa06) gates a 25,000-channel x 10-programme EPG
  window with recycled rows and memory sampling. It has no player-reuse ledger
  and no timeshift byte/segment retention model.
- QA-31, QA-32, QA-34, QA-35, QA-37, QA-38, QA-39, QA-40, QA-36 and QA-33 are
  pinned git-object / static-needle gates over sibling merge chains. QA-41
  (hermes/worker-c24-qa41) is a 20-case recording-outcome matrix with seven
  DVR policy markers. None of them model playback, EPG windows and timeshift
  retention together.
- Core JVM tests (TimeshiftWindowTest, TimeshiftCaptureTest,
  PlayerReusePolicyTest, HlsCaptureEngineTest, DashCaptureEngineTest) test one
  seam at a time. This gate is a byte-pinned, fixture-driven combined contract
  with an explicit negative control; it does not re-run or copy those tests.
- No scripts/qa42 or playback-soak42 path exists on any sibling branch, so no
  equivalent gate was silently duplicated.

Model and checks
----------------
298 checks per run: strict schema/digest checks, 8 sampled contiguous EPG
channels, 4 page bounds, 8 recycled-slot samples, 96 per-step reuse decisions,
160 per-arrival retention decisions, both retention caps proven binding (129
byte evictions, 20 count evictions, peak 12 segments), one oversize refusal,
live playlist without an end tag, finish-deletes-all, six pinned coverage
counts and a privacy scan. All names are synthetic and bounded (<6 KiB per
fixture file). Device/network soak is NOT RUN.

Commands and exact observed status (base 06a9ab1)
--------------------------------------------------
  bash -n scripts/qa42/run.sh                                   PASS
  python3 -m py_compile scripts/qa42/Qa42Gate.py mutate.py      PASS
  bash scripts/qa42/run.sh gate                                 EXIT=0
      SUMMARY checks=298 pass=298 fail=0 first=NONE verdict=PASS
      COUNTS epg=96 programs=4608 soak=96 reuse=58 segments=160 evictions=150
  bash scripts/qa42/run.sh                                      EXIT=0
      RED    rc=1 first=POLICY_MARKER fail=1 checks=298
      GREEN  rc=0 pass=298 checks=298 fail=0
      RESTORE PASS committed-fixtures-byte-identical
      MUTATION PASS one-policy-marker-mutant-detected-and-committed-fixtures-untouched
  export ANDROID_HOME=C:/Android/Sdk JAVA_HOME=<jdk17>
  ./gradlew :app:testDebugUnitTest --offline --rerun-tasks       EXIT=0
      BUILD SUCCESSFUL in 34s
      JUnit XML: suites=1 tests=5 failures=0 errors=0 skipped=0
      suite com.devtunasoft.tuneros.qa42.Qa42SoakContractTest tests=5

The single mutation changes `byte_budget_enforced` from 1 to 0 in a scratch
copy of markers.tsv only. Mutant mode replaces the byte-identity check with a
scope check so the named semantic failure is first; committed fixture bytes
are hashed before and after and never written. Exit codes: 0 PASS, 1 FAIL,
2 setup error.

Bounds and honesty
------------------
The report carries check ids, counts and digests only: no URLs, credentials,
payload bytes or fixture prose. No real media, database, provider or device is
touched. A host contract is not evidence of decoded video frames, sustained
PSS/battery or real network playback; those remain UNVERIFIED locally.
