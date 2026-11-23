QA-04 - scheduled-recording durability regression fixture
========================================================

Owned paths: qa/fixtures/qa04-rearm/**, scripts/qa04-deterministic-checks.sh.
No app/src/main, app/src/test, core or shared Gradle/manifest file is modified.

What it checks (complements, never duplicates, the emulator-self-test alarm smoke check)
---------------------------------------------------------------------------------------
1. storeRoundTrip450PlusNoSilentRowLoss
   461 synthetic schedule rows are inserted through the unchanged production
   RecordingScheduleStore, reopened, and visited with 200-row keyset pages.
   Asserts exact field-for-field equality, no duplicates, no silent row loss,
   three bounded pages, a measured maximum of 200 rows per single query, plus
   duplicate/invalid-range negative cases.
2. startupRearmRegistersExactAlarmsForStoredFutureSchedules
   Seeds five future SCHEDULED rows plus ended, cancelled and PREPARING rows.
   Calls the production RecordingScheduler.rearmPending and asserts the real
   AlarmManager stub received exactly five RTC_WAKEUP registrations at each
   row's exact start time - the existing smoke check only inspects the stored
   exact_alarm flag, not the platform registration. Also checks telemetry,
   cancellation and bounded reads.
3. upgradePreservesDurableScheduleRows
   Seeds 461 mixed-state rows, closes every connection, reopens the same
   scratch database through a new store instance (the state a replacement APK
   sees on first launch), and asserts every row and the schema version survive.
   Rearm is repeated after the reopen.

RED then GREEN
--------------
Run scripts/qa04-deterministic-checks.sh. It first injects three regressions
into the harness only - a query that drops every 37th row, an AlarmManager
that silently drops registrations, and an upgrade that clears durable rows -
and requires all three assertions to FAIL (RED evidence). It then runs the
same assertions unchanged against the unmodified production code and requires
all three to PASS (GREEN evidence). No production fix is needed or attempted:
app/src/main is owned by another worker in this worktree.

Command and evidence
--------------------
  cd <worktree>
  bash scripts/qa04-deterministic-checks.sh

Reports are written to ${TMPDIR:-/tmp}/qa04-jvm/report-mutation.txt and
report-normal.txt. The script prints every PASS/FAIL/SUMMARY and DETAIL line.

Local toolchain: the harness uses a pinned local JDK 17 and Kotlin 2.0.21
compiler if present, with env overrides QA04_KOTLINC, QA04_JUNIT_JAR,
QA04_HAMCREST_JAR, QA04_PYTHON and QA04_SCRATCH. SQLite is the Python stdlib
sqlite3 engine; all databases are scratch files under QA04_DB_DIR and are
deleted at startup. No provider URL, credential, DRM key or recording payload
appears in fixtures, reports, commits or chat.

Device portion: NOT RUN locally - no Android SDK and no adb/emulator are
installed on this host (ANDROID_HOME/ANDROID_SDK_ROOT unset, `adb` missing).
The existing emulator self-test alarm smoke checks were not modified.
