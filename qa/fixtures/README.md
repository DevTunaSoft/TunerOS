# Large EPG QA fixture (QA-02 / PERF-01)

This directory contains a deterministic, synthetic EPG fixture generator and its
build helper. **No generated fixture data is checked in** — a 25,000-channel /
250,000-programme XMLTV file is tens of megabytes and would bloat the repository.

## Files

- `scripts/generate_qa_epg_fixture.py` — fixed-seed generator (`SEED = 20261123`).
  Streams to disk with a hard ceiling of 128 MiB total output.
- `qa/fixtures/core-classpath.init.gradle.kts` — print-only Gradle init script that
  exposes the existing `:core` runtime classpath to the harness. It registers one
  task and changes no source set, dependency or core file.
- `scripts/QaFixtureCheck.java` — standalone harness calling the existing core
  entry points `PlaylistParser.parseReport`, `GuideParser.parse`,
  `ChannelVisibility.filter` and `GuideSnapshot` index/window APIs.
- `scripts/qa_epg_fixture_check.sh` — end-to-end runner (generate twice, compare
  SHA-256 for determinism, compile harness, run with bounded heap).

## Fixture shape

- 25,000 channels, all with reserved `.invalid` stream URLs and no real provider,
  credential, logo, DRM key or media byte.
- 25,000 XMLTV `<channel>` declarations; the last 250 have **no programmes**, so
  hide-no-EPG filtering must hide exactly those 250.
- 250,000 programmes over the first 24,750 channels. The first 2,500 channels get
  11 programmes and the rest get 10, which sums to exactly 250,000.
- Every channel has one programme ending at the frozen anchor and the rest
  starting at or after it. The anchor is fixed at 2030-01-01T00:00:00Z and is
  recorded in `manifest.properties`; the check never uses the wall clock.

## Run locally

```bash
export JAVA_HOME=/path/to/jdk17   # Gradle wrapper needs JDK 17
bash scripts/qa_epg_fixture_check.sh
```

Generated files land in `build/qa-epg-fixture/` and are disposable. The harness
prints one `PASS` line per assertion and a final `LARGE EPG FIXTURE CHECK PASS`.
Peak heap is sampled throughout the import and bounded by `max_heap_bytes` in the
manifest; Linux peak RSS (`VmHWM`) is additionally bounded by `max_rss_kb`.

## Audit: why this is not a duplicate

Existing coverage was read before adding anything:

- `.github/workflows/android.yml` has only `build` and `emulator-self-test` (one
  Android 35 phone emulator). `android-tv.yml` has `build` and `emulator-tv-checks`
  (one Android 36 TV emulator). No job generates or imports a checked-in large EPG.
- `scripts/emulator-self-test.sh` already imports a 3-channel / 6-programme loopback
  fixture and, with a dispatch-only public URL, a ~36k-programme public guide. It also
  runs the in-app 160-channel / 11,520-programme `debug-large-guide-stress` and
  `guide-scale-complete` probes. Those are smaller, on-device and partly dispatch-only.
- `scripts/emulator-tv-checks.sh` generates a 3-channel / 9-programme guide at runtime
  purely for a screenshot and layout measurement.

This change adds **one bounded JVM step** to the existing `build` job — no second
workflow, no second emulator and no extension of the dispatch-only public-feed path.

## What this does not prove

This is a bounded JVM parser/resource check, not physical Android TV memory,
sustained playback, real-provider import or decoded-frame acceptance.
