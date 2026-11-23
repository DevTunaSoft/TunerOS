# QA-03 playback regression and switching soak fixture

Bounded JVM harness for deterministic channel-switch and stream-error regression.
It is **not** a video decoder test and does not replace the on-device first-frame
check in `scripts/emulator-self-test.sh`.

## Files

- `scripts/QaPlaybackSoakCheck.java` — standalone harness. Calls only existing
  `:core` entry points: `PlaylistParser`, `PlayerReusePolicy`, `StreamDoctor`,
  `StreamEvidence` and `TransportFault`.
- `scripts/qa_playback_soak_check.sh` — runner. Reuses the print-only Gradle init
  script in `qa/fixtures/core-classpath.init.gradle.kts`, compiles with `javac`,
  runs with `-Xms32m -Xmx256m` and a 120-second timeout, and performs a shell-side
  privacy scan with a negative control.
- One new step in the existing `build` job of `.github/workflows/android.yml`.
  No new workflow, no second emulator and no extension of the dispatch-only
  public-feed path.

## Fixture reuse

When the QA-02 step has already run, the harness reads the first 128 channels of
`build/qa-epg-fixture/playlist.m3u` through `PlaylistParser`. Those stream URLs are
reserved `.invalid` addresses and are never contacted or printed. If that fixture
is absent, the runner generates an equivalent 128-channel scratch playlist under
`build/` so the check stays idempotent when run alone. The transport source is a
short-lived `127.0.0.1` HTTP server created inside the harness: two daemon handler
threads, bounded 4 KiB reads, 2-second connect/read timeouts, explicit `stop(0)`,
pool shutdown and a join assertion. No external network or provider data is used.

A synthetic QA frame is four magic bytes plus a fixed index inside a 512-byte
payload. Observing that marker is a **controlled first-frame gate for the fake
player**, not evidence of a decoded Media3/H.264 video frame.

## Assertions

1. `playback-loopback-first-frame` — a bounded HTTP read observed all 16 synthetic
   frame markers (512 bytes) from the loopback source.
2. `playback-soak-determinism` — 128 fixed switching decisions are replayed twice;
   the event trace SHA-256 and counters must match.
3. `playback-soak-no-leaked-players` — every reuse decision goes through
   `PlayerReusePolicy`; created == released, at most one live player, and 12
   pre-first-frame preemptions must release cleanly.
4. `playback-soak-no-leaked-threads` — no `qa-playback*` thread survives the
   server close; total thread count must not grow by more than four.
5. `playback-failure-taxonomy` — 24 fixed Media3 codes map to the existing
   `StreamDoctor` labels; hostile evidence containing synthetic provider URL,
   credential, DRM key and recording-payload tokens is reduced to a fixed
   transport label and a report of at most 400 characters.
6. `playback-loopback-failures` — real loopback 404, `text/html` and short-body
   responses are classified through `StreamDoctor` without leaking tokens.
7. `playback-soak-bounded-memory` — sampled heap growth below 48 MiB under a
   256 MiB heap cap.
8. `playback-privacy-no-sensitive-tokens` and the runner's report scan — none of
   the four synthetic sentinels appear in the emitted report; a planted sentinel
   must be detected, proving the scan is live.

## Audit: why this is not a duplicate

- The existing phone-emulator script already performs one real Media3 first-frame
  check and two playback diagnostics (missing path and HTML page) in
  `scripts/emulator-self-test.sh`. Those remain untouched; this harness does not
  launch an app, an emulator or a decoder.
- Existing `StreamDoctorTest`, `StreamEvidenceTest` and `PlayerReusePolicyTest`
  cover individual decisions. This harness adds a fixed 128-step replay with
  player/connection/thread lifecycle accounting, loopback transport and
  end-to-end report privacy scanning.
- QA-02 `scripts/qa_epg_fixture_check.sh` already owns the large EPG import. This
  harness only borrows its generated playlist as channel identities.

## Verification note (thread-cleanup regression)

A repeat run exposed an intermittent `loopback handler threads survived close` failure:
`ExecutorService.awaitTermination` ended the tasks, but a pool thread could still appear
in `Thread.getAllStackTraces()` for a moment. `Loopback.close` now joins the owned
threads after `awaitTermination` and asserts each is dead; the check is stronger, not
weakened. Six consecutive local runs after that change passed (3 of 4 runs failed before).

## What this does not prove

No Android runtime, hardware decoder, DRM license, real provider stream, real
recording payload, physical device, sustained PSS/battery or DVR service lifecycle
is exercised. On-device first-frame and stream-error evidence remains as recorded
in `docs/ON_DEVICE_QA.md` and is **UNVERIFIED** locally because the Android SDK is
absent in this environment. Output files are disposable and live under `build/`.
