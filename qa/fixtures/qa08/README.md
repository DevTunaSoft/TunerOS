# QA-08 — bounded DVR overlap and series-rule expansion gate

Deterministic, zero-dependency host gate for the DVR scheduling contracts: half-open and padded
conflict windows, earliest-active-overlap selection, terminal-state reservations that must not
block, duplicate series rules, cap enforcement and bounded expansion over a large scratch guide.
It complements QA-04 (rearm with isolated SQLite), QA-05 (small guide search), QA-06 (large EPG
layout) and QA-07 (PIN and bounded lists). No product source, core source, dependency, Gradle file,
workflow or shared document is changed.

## Fixture contract

`scripts/qa08/Qa08Gate.java` generates five byte-identical scratch files into `qa/fixtures/qa08/`:

- `schedules.tsv` — 480 bounded rows (limit 600): scratch ids `qa08sch-*`, 64 scratch channels,
  overlapping windows on a fixed grid, active and terminal states, and 0 or 60-second capture pads;
- `rules.tsv` — 64 rule rows (limit 80) with duplicate title/channel keys; 57 distinct keys, of
  which only 50 survive the rule cap;
- `programs.tsv` — 900 guide rows (limit 1000), including expired, unmatched and deliberate
  duplicate channel/start pairs;
- `manifest.txt` — pinned counts and derived model values (11,574 raw conflict pairs, 369
  padded-only pairs, 500 planned expansions, 159 conflict-skipped, 83 terminal-only overlaps);
- `sha256.txt` — SHA-256 of the four data files.

`verify` regenerates every byte in memory, strict-parses the committed files with fail-closed
codes, and independently re-derives every pinned value. All identifiers are scratch names; no
stream address, credential, PIN or recording payload is used. Only check ids, counts and digests
are printed.

## Checks

Host verifier: 18 positive checks (byte identity, SHA pin, strict parse, row bounds, raw/padded
conflicts, expired and unmatched windows, duplicate rules, rule cap, distinct rules, expansion
dedupe, expansion cap, conflict skip, reserved count, terminal rows, overlap-selection digest,
privacy) and 6 in-memory negative controls (half-open boundary, padding-only detection, rule-cap
overflow, terminal exclusion, leak detector, fail-closed code).

Compiled assertions: the same 12 named DVR checks in `Qa08Assertions.kt`, run on the JVM by the
runner and on device by `Qa08Instrumentation` through a test-APK-only manifest entry.

## Negative-mutation RED controls

Each control copies the committed fixtures to scratch, applies one corruption, recomputes the
fixture hashes, and requires a nonzero verifier exit with the named failure code. The real
fixtures must then pass. A mutation that is accepted is a gate FAIL.

| Mutation | Required rejection |
|---|---|
| `row-overflow` | `ROW_BOUND` |
| `interval-corrupt` | `INTERVAL` |
| `privacy-ref` | `PRIVACY_REF` |
| `rule-cap-tamper` | `RULE_CAP` |
| `expansion-cap-tamper` | `EXPANSION_CAP` |
| `terminal-state-tamper` | `TERMINAL_STATE` |
| `padded-conflict-tamper` | `PAD_CONFLICT` |
| `duplicate-rule-tamper` | `DUPLICATE_RULE` |

## Files

- `scripts/qa08/Qa08Gate.java` — generator, strict verifier and eight mutations.
- `scripts/qa08-gate.sh` — bounded runner (`verify` by default, `generate` to refresh fixtures).
- `qa/fixtures/qa08/` — scratch fixtures, byte-pinned manifest and `.gitattributes` disabling EOL normalization.
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa08/Qa08Assertions.kt` — 12 compiled named checks.
- `app/src/androidTest/java/com/devtunasoft/tuneros/qa08/Qa08Instrumentation.kt` — platform `Instrumentation` runner.
- `app/src/androidTest/AndroidManifest.xml` — test-only runner registration.

## Status vocabulary

Host verification and compilation are recorded from real command output in the worker heartbeat.
A missing adb binary or authorized device is `BLOCKED / NOT RUN` for device execution; a compiled
test APK is never presented as device acceptance.
