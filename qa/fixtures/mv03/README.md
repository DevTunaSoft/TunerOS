MV-03 — two-tile multiview UI acceptance (scratch fixtures only)
===============================================================

Purpose
-------
Bounded, deterministic coverage of the *existing* two-tile multiview policy in the
real app UI. It proves that two bounded tiles can be shown and released cleanly,
that a third distinct channel is refused without changing audio, and that exactly
one tile is presented as audible. Platform speaker output is not asserted: the
app's one-audible-tile state and one attached tile player are asserted instead.

Owned paths
-----------
app/src/androidTest/**  — zero-dependency platform-Instrumentation runner and assets
qa/fixtures/mv03/**     — host-side gate, fixture renderer, prerequisite probe, this note
scripts/**              — bounded runner

No app/src/main, core, Gradle, manifest, workflow, docs or README file is changed.
No test dependency is added: the runner extends android.app.Instrumentation only.

Checks (all six are required)
-----------------------------
mv03-loopback-import          3 scratch channels imported from 127.0.0.1 fixture
mv03-two-bounded-tiles-shown  opt-in on; exactly tile 1 audible + tile 2 muted, both visible
mv03-third-tile-refused       third selection refused; still two tiles, one audible
mv03-single-audible-tile      clicking the muted tile leaves exactly one audible tile
mv03-tiles-released-cleanly   opt-out hides the row and detaches both tile players
mv03-relaunch-has-no-tiles    fresh activity is Classic Cable with no attached tile player

Precondition
------------
The app must already expose the two-tile UI (MV-02): the "Multiview: off/on"
button, the "Video tile N, ..." accessibility labels and the "Two tiles are open"
/ "Sound switched to the focused tile" status text. The prerequisite probe checks
this deterministically and the runner reports BLOCKED when it is absent. This MV-03
branch intentionally does not add that production code.

Commands (Windows MSYS bash, from any directory)
------------------------------------------------
  cd C:/Users/X1/TunerWork/workerB5
  bash scripts/mv03_ui_acceptance.sh

The runner:
  1. verifies the owned source and loopback-only fixtures (host side);
  2. probes the app UI contract (host side; BLOCKED when MV-02 is not integrated);
  3. renders the same fixture twice and compares SHA-256 (host side);
  4. requires adb, Java 17, Android SDK path and exactly one authorized device;
  5. compiles :app:assembleDebug :app:assembleDebugAndroidTest, installs both APKs;
  6. runs six checks with `am instrument -w -r` under a 300 s timeout;
  7. asserts the six PASS lines, zero FAIL, "6 PASS\t0 FAIL" and
     INSTRUMENTATION_RESULT: mv03_failures=0.

Fixture contents
----------------
playlist.m3u.tmpl / guide.xml.tmpl contain three channels named QA MV03 One/Two/Three,
six programmes and only http://127.0.0.1:{{PORT}}/... URLs. The in-test ServerSocket
answers only /playlist.m3u, /guide.xml and 404 for scratch stream paths. No provider
URL, credential, real media, private database or human action is involved.

Status vocabulary
-----------------
PASS          exact command output on a named device/branch showed the check passing.
FAIL          exact command output showed an assertion failing.
BLOCKED       prerequisite, SDK, adb or device missing; no device claim is made.
NOT RUN       command was not invoked.
UNVERIFIED    observed but not proven (e.g. actual speaker output on hardware).

Reports: build/mv03/report.txt and build/mv03/instrumentation.txt (untracked output).
