# TunerOS — Hermes active development roadmap

**Canonical repository:** https://github.com/DevTunaSoft/TunerOS  
**Original history and rationale:** [ROADMAP.md](ROADMAP.md) — retained, not replaced.  
**Handoff:** [AGENTS.md](../AGENTS.md), [HERMES_AUTONOMOUS_TAKEOVER.md](HERMES_AUTONOMOUS_TAKEOVER.md), [HERMES_WORK_LEDGER.md](HERMES_WORK_LEDGER.md).  
**Native Hermes watchdog:** [HERMES_INTERNAL_CRON.md](HERMES_INTERNAL_CRON.md).  
**Release state:** early alpha. Do not claim full device certification or finished product from synthetic tests.

This is the *actionable*, ordered roadmap. The older `docs/ROADMAP.md` is the original decisions/change history. Hermes must fully read both and reconcile every item against actual `main`, code, branches, PRs, CI and tests **before** implementing. Tasks below are candidates until a live audit proves they are not already implemented or assigned. Avoid duplicate features and duplicated PRs.

## Non-negotiable operating model

- **Minimum two actually active engineering workers at all times while Hermes's autonomous execution environment is operating, access is valid and unblocked tasks exist.** The Hermes supervisor/coordinator does not count toward two. More workers are allowed when parallel tasks have disjoint file ownership and host resources permit. Do not spin up unnecessary heavy emulators merely to satisfy a worker count.
- **Every five minutes, use Hermes's OWN INTERNAL CRON/SCHEDULER** with `*/5 * * * *` to check active workers, progress, failed/stalled work, test/CI outcomes and handoff state. Do not install a Windows scheduled task, Linux crontab or standalone watchdog for this. Discover its actual supported syntax/API and register **one** idempotent job. See [HERMES_INTERNAL_CRON.md](HERMES_INTERNAL_CRON.md).
- Reserve exact files/tasks in the single ledger before launch. Each worker uses a dedicated Git worktree/branch. Integrator serializes docs, shared Gradle, manifest and Activity changes. A third/fourth worker may own separate `core/`, tests, security or performance tasks only when there is no conflict.
- **Continuous progress:** a finished worker immediately picks the next unblocked task; replace failed sessions without duplicating or overwriting work. The five-minute cron is a *backstop*, not a reason to wait five minutes before continuing.
- No repeated owner prompts for routine coding PC usage, trusted tooling installs, tests, commits, pushes or PRs. Use actual available permissions; do not bypass security controls, buy subscriptions, leak provider credentials or delete real media.
- Never overstate tests: use PASS, FAIL, BLOCKED, NOT RUN and UNVERIFIED, tied to exact commit/CI run/device. Preserve low RAM/CPU/storage/battery. Android emulators ≠ physical TV acceptance.

## Baseline to reconcile on first run

- `main` observed at `a523c2e43d198ee752f7c4f03f9f7853356ffb30` at handoff creation.
- Android build/test on that head **PASS**: https://github.com/DevTunaSoft/TunerOS/actions/runs/45011055536
- Recent PR Android TV suite **PASS**: https://github.com/DevTunaSoft/TunerOS/actions/runs/45010167847
- The handoff is submitted as [PR #118](https://github.com/DevTunaSoft/TunerOS/pull/118); reconcile its current CI and merge state before starting.
- The Kotlin/Android code already includes M3U/XMLTV parsing, indexed EPG, Classic Cable guide/search, Media3 player, Stream/Guide Doctor, DVR/HLS/DASH subsets, schedule recovery, timeshift, parental controls, backups, and `MultiviewSession` policy. **Do not confuse core multiview policy with actual multi-tile video playback.**
- Open risks: TV guide compact-row layout, actual two-tile playback/audio handling, full real-device acceptance, sustained resource use, physical SD/USB and power-loss fault handling. Existing synthetic and public-fixture evidence must be checked before assuming each is untested.

## Milestones and completion gates

| ID | Milestone | Must be proven to finish | Initial assessment |
|---|---|---|---|
| M0 | Autonomous takeover, 2+ active workers and native five-minute cron | Complete full-document reading/audit; 2 running distinct workers; native cron installed and verified by at least one real invocation; failure/replacement proof; owned worktrees/branches | **PARTIAL** — 2 real workers running (verified 2026-11-23T05:59:26Z); native cron job `0f160f15b5a4` installed and a real fire observed; owned worktrees/branches active. Full engineering-corpus reading and auto-recovery exercise still NOT RUN/UNVERIFIED. |
| M1 | Reproducible build and tests | Core unit tests, debug APK, Android phone emulator, Android TV emulator pass on actual head SHA; artifact and logs captured | **Historical PASS**, new head unverified |
| M2 | Functional Classic Cable guide | D-pad focus, OK/select-to-play, channel search, user-controlled hide-no-EPG, future-only schedules, scroll/rotation, genuine compiled UI bounds | **PARTIAL** |
| M3 | Playback and usable multiview | Controlled first-frame playback, rapid switches, 2 actual video tiles, exactly 1 audible tile, limit and cleanup verification | **PARTIAL / multiview device UNVERIFIED** |
| M4 | Reliable DVR/PVR, schedule and timeshift | Byte-exact direct-to-disk recording, cancel/recovery, series, rearm/Doze/reboot, SAF failure and storage tests | **PARTIAL / hardware UNVERIFIED** |
| M5 | Resource acceptance | Representative 25k-channel / 250k-programme import, 1/2 GiB device/emu profiles, repeated PSS/CPU/jank/soak and no leaks | **PARTIAL / sustained physical UNVERIFIED** |
| M6 | Complete polished UX and accessibility | Approved Classic Cable skin/branding, readable TV/phone layout, focus/accessibility, large fonts and overscan, no regressions | **PARTIAL** |
| M7 | Release and handoff | Privacy/security/provenance, signing and upgrade, docs, validated artifacts, exact-SHA ledger and outstanding hardware limits | **NOT RUN as release candidate** |

## Prioritized task queue (reassess after each integration)

| Task ID | Priority | Candidate worker | Task (verify absence first) | Concrete acceptance |
|---|---|---|---|---|
| OPS-01 | P0 | Hermes | Read complete engineering corpus, audit source/CI/history and PR #118 | Hash/line tracking, duplicate check, verified base SHA |
| OPS-02 | P0 | Hermes | Configure 2+ isolated worker sessions and Hermes-native `*/5 * * * *` watchdog | Actual workers count >=2; cron job listed, runs, detects loss, launches/reassigns when supported |
| QA-01 | P0 | Worker B | Compiled TV guide layout and D-pad focus across loaded/unloaded states, follow PR #97/#98 | APK screenshot and view bounds with automated pass/fail; no invented visuals |
| PLAY-01 | P0 | Worker A | Verify and correct channel activation from guide, remote OK and touch | Selected channel renders controlled first frame; no duplicated player |
| QA-02 | P0 | Worker B | EPG import, no-EPG filter, name search, rotation and lifecycle | Deterministic realistic large guide fixture; UI responsive; future-only channels remain visible |
| MV-01 | P0 | Worker A | Implement lean actual two-tile Media3 video UI if still missing | Two tiles stream simultaneously on supported emulator/device and release cleanly |
| MV-02 | P0 | Worker A | Exactly one selected multiview audio source, decoder/provider limits | Audio selection tests, resource refusal and fallback, no audio overlap |
| QA-03 | P0 | Worker B | Real playback regression fixture and switching soak | Video first-frame, failure taxonomy, lifecycle, no URL/credential leakage |
| DVR-01 | P0 | Isolated app worker | DVR service destruction/cancel/catalog-finalization and partial-file fault injection | No completed empty file, no double write, graceful recovery and bounded I/O |
| QA-04 | P0 | Worker B | Doze, exact alarm, 450+ schedules, reboot/rearm and app upgrade | Deterministic Android device/emulator evidence, correct durable schedule rows |
| DVR-02 | P0 | Isolated app worker | SAF storage failure/perms/disk-full/disconnect if testable | Never touch real recordings; failure recorded and no silent path fallback |
| PERF-01 | P1 | Worker B | Low-memory playlist/EPG load benchmarks | Repeated PSS/heap/CPU/latency measures with reproducible input/limits |
| PERF-02 | P1 | Worker B | Multiview, DVR and rapid switching sustained load | Quantified max sampled PSS and cleanup, no growing threads or buffers |
| UX-01 | P1 | Worker A | Classic Cable guide resizing, accessible focus and readability | Real build screenshots and objective clipping/focus tests; preserve approved styling |
| UX-02 | P1 | Separate non-overlap worker | Parental PIN, backups and series/timeshift controls end-to-end | Compiled app picker/dialog tests, safe cancel/restore |
| CORE-01 | P1 | Optional Worker C | Provider timezone settings and DST correctness, if still absent | Core tests for ambiguous/offset times; visible warning behavior |
| SEC-01 | P1 | Optional Worker D or Hermes reviewer | Credential hygiene, XML XXE, HTTPS redirects, secure backup/diagnostics, provenance | Negative test evidence; no secrets or media logged |
| REL-01 | P1 | Hermes | Signed APK/release process, upgrade & migration acceptance | Reproducible signed build with controlled-data upgrade evidence |
| REL-02 | P1 | Hermes | Honest feature status, user manual and developer handoff | Every current claim linked to commit, test and actual limitations |
| LATER-01 | P2 | After essentials | Additional selectable skins/profiles with approved design | Retain Classic Cable as default, user-visible selection tests |
| LATER-02 | Deferred | Unassigned | FastChannels on-device serving | Revisit only after core acceptance |
| LATER-03 | Deferred | Unassigned | SAMPLE-AES / protected unsupported paths | Do not restore previously reverted unverified implementation without valid reference acceptance |

## Suggested independent work distribution

**Worker A:** owns narrowly specified product paths in `app/src/main/**`; `MainActivity.kt` only if integrator explicitly reserves it. Focus PLAY-01/MV-01/MV-02 and targeted regressions. No edits to workflows, scripts or shared docs.

**Worker B:** owns `scripts/**`, `.github/workflows/**`, `app/src/androidTest/**` and genuinely independent QA fixtures. Focus QA-01, QA-02, QA-03, QA-04 and PERF-01. No edits to A's product paths.

**Worker C (optional):** separately assigned `core/src/**` files and tests with no A overlap, especially performance, parser/security and clock policies.

**Worker D (optional):** code review, dependency/license audit, performance tracing or unrelated tests with explicitly distinct files. Do not count a passive reviewer or unresponsive model as an active coding worker.

**Hermes:** owns integration, issue/task dispatch, shared docs/ledger, PR merge sequence and the internal cron. More than two workers are welcomed only if productive and the coding PC can support them.

## Finish/recovery loop

For every assigned task: reproduce the defect or prove the missing feature; write acceptance tests; make minimal modular changes; run local/core tests and relevant Android emulator/TV tests (on PC and/or GitHub Actions); examine memory/CPU/allocations; commit and push; open/reconcile PR; verify exact-head CI; merge safely when green; update the single work ledger and feature/QA docs; assign the next non-overlapping task. A worker completing an assignment must be given a new task immediately, not parked.

After two equivalent failures, inspect source/logs and change approach. After two cycles without measurable progress, record a genuine blocker and move the worker to an independent task. If any worker is missing, the **internal five-minute cron** reconciles actual sessions, handles recovery if supported, and records the minimum worker count. Never assert 24/7 uptime unless the Hermes scheduler and runtime truly remain available.
