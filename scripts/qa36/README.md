QA-36 - host-only deterministic gate for HTTP-07 malformed-URL redaction
========================================================================

Owned paths only: scripts/qa36/**, qa/fixtures/qa36/**. No product source,
androidTest, other QA directory, workflow, Gradle or shared document is changed.
No PR is opened or merged by this worker. Base worktree SHA:
a523c2e43d198ee752f7c4f03f9f7853356ffb30.

Pinned inputs (full SHA; a moving branch name is never trusted)
---------------------------------------------------------------
HTTP07_BASE 22f0fc3bec80aba07550862dbba430c8ae647190 parent of HTTP-07
HTTP07      197520e59cbc1ede6e8234d17730247882731e79 PR 218, source URL hygiene
CORE49_BASE 6c8fed5cd3348f73ca1b13e502181211be9900a8 parent of CORE-49
CORE49      85563da9200eff57c5c34ab271d6ecc67db4ae18 PR 208, HLS URI redaction

Audit boundary (no duplication)
-------------------------------
Cross-branch grep over every QA-29..35 sibling script/fixture tree returned zero
hits for HTTP-07, http07, SourceHttp, MALFORMED_SOURCE or the fixed refusal text.
QA-29 gates DVR terminal-state transitions; QA-30 compiled TV-guide instrumentation;
QA-31 a static-needle DVR-03/REL-01 gate; QA-32 a compiled MAP-04 + CORE-49
behavioural probe; QA-33 and QA-35 single-marker host gates; QA-34 a dual-hazard
gate. CORE-49 is only asserted structurally here (guard present at the fix,
absent at its parent, two adversarial tests, core-only delta); the compiled
behavioural probe remains QA-32's. This gate covers the distinct app-side
SourceHttp invariants. No full engineering-book reading is claimed.

What is verified
----------------
- Both URL construction sites are wrapped in try/catch in order:
  URL(value) before the first guard, URL(current, location) before the second.
- Both catches throw exactly the fixed MALFORMED_SOURCE refusal (2 occurrences);
  neither binds an exception variable, reads .message/localizedMessage, nor
  interpolates $value or $location. The pre-fix parent has raw URL construction
  and no guard.
- The redirect failure path disconnects then releases the connection before the
  fixed refusal; the app/core fix deltas stay disjoint (app-only vs core-only).
- The 3 HTTP-07 regressions exist by exact method name, with exactly 3 @Test
  annotations and 4 synthetic HTTP-07 canary markers.
- CORE-49 guard and its 3-marker adversarial test are present at 85563da, absent
  at its parent, and touch only core/.

Commands
--------
  bash -n scripts/qa36/run.sh
  python3 -m py_compile scripts/qa36/mutate.py
  bash scripts/qa36/run.sh gate-only    pinned git objects, GREEN checks only
  bash scripts/qa36/run.sh verify       one scratch mutation RED, byte-identical
                                        restore, restored GREEN, clean GREEN

Bound and privacy
-----------------
Only the four declared pinned commits are read; each object is capped at 131072
bytes / 4000 lines. No emulator, no network, no product edit. The report carries
check ids, counts and hashes only; report and fixtures are scanned for URL,
credential and secret patterns. No provider URL or credential appears in any
fixture; only synthetic marker names are used. The mutation replaces exactly one
declared (ref, basename) scratch overlay file and fails closed if it is absent.

Mutation control
----------------
The redirect catch is rewritten in a scratch copy from
throw IllegalArgumentException(MALFORMED_SOURCE) to a leaky interpolation of
the exception message and Location. RED must exit nonzero with first failure
H_FIXED_REFUSAL_BOTH_SITES; the overlay is then restored byte-identically and
both the restored run and the clean run must be GREEN.

Reading record
--------------
Read for this task: AGENTS.md (shared scratch copy; absent at the worktree root),
CLAUDE.md, docs/ROADMAP.md, HERMES_EXECUTION_ROADMAP.md, HERMES_INTERNAL_CRON.md,
HERMES_WORK_LEDGER.md (shared scratch copies), the TunerOS internal-cron
checkpoint, the deterministic-qa-gates skill and its byte-exact-fixtures
reference, QA-31/QA-32/QA-33/QA-35 README and runner prior art, and the complete
HTTP-07 and CORE-49 commit diffs. Full engineering-book reading is NOT claimed.
