# TunerOS — Hermes Authoritative Development Work Ledger

**Coordinator:** Hermes (single integrator). **Source:** https://github.com/DevTunaSoft/TunerOS. **One ledger only:** update this file, not parallel per-agent copies.

This is the durable state and task-ownership record for autonomous development. **Snapshot below is factual only as of 2026-11-22 and must be reconciled against live GitHub and local worktrees at each restart.** New tasks must include exact commits and test links; unverified claims are prohibited.

## A. Takeover audit baseline

- Base `main` SHA observed: `a523c2e43d198ee752f7c4f03f9f7853356ffb30`.
- GitHub repo: public `DevTunaSoft/TunerOS`, branch `main`; connected GitHub account has push access.
- Open PRs at audit: **0** (`GET /repos/DevTunaSoft/TunerOS/pulls?state=open`); issue search returned no open issues.
- Exact-main Android build/test workflow: **PASS**, run https://github.com/DevTunaSoft/TunerOS/actions/runs/45011055536 .
- Recent PR-target Android TV: **PASS**, run https://github.com/DevTunaSoft/TunerOS/actions/runs/45010167847 at `a523c2e`.
- Recent PR-target Android build: **PASS**, run https://github.com/DevTunaSoft/TunerOS/actions/runs/45010167864 at `a523c2e`.
- Exact-main physical Android TV + phone acceptance: **UNVERIFIED**; long real-provider recording/USB removal/real reboot/2 GiB sustained playback acceptance: **UNVERIFIED**. No new data from the handoff itself.
- `AGENTS.md`, `CLAUDE.md` and `docs/HERMES_AUTONOMOUS_TAKEOVER.md` define mandatory onboarding and authorization. The original engineering books remain canonical for product rationale.
- Current committed handoff branch: `docs/hermes-autonomous-takeover-20261122`. This is **documentation only**. It is not evidence of new TunerOS app functionality.

## B. Required reading ledger (complete in actual Hermes session)

Hermes must fill in completion status, actual Git blob/SHA or content hash, lines read, and date. **All entries are NOT YET VERIFIED as read by Hermes.** Read sequentially, no "reviewed summary" substitute.

| File | Hermes full reading | Worker A | Worker B |
|---|---|---|---|
| `AGENTS.md`; `CLAUDE.md` | NOT RUN | NOT RUN | NOT RUN |
| `docs/HERMES_AUTONOMOUS_TAKEOVER.md`; this ledger | NOT RUN | NOT RUN | NOT RUN |
| `docs/engineering-book/START_HERE.md` | NOT RUN | NOT RUN | NOT RUN |
| `docs/engineering-book/MASTER_HANDOFF.md` (all volumes) | NOT RUN | NOT RUN | NOT RUN |
| `docs/engineering-book/ENGINEERING_CODING_DOSSIER.md` (all sections) | NOT RUN | NOT RUN | NOT RUN |
| `docs/engineering-book/DEEP_ENGINEERING_BOOK_VOLUME_III.md` (all chapters) | NOT RUN | NOT RUN | NOT RUN |
| `README.md`; `docs/HANDOFF.md`; `docs/FEATURE_STATUS.md` | NOT RUN | NOT RUN | NOT RUN |
| `docs/ROADMAP.md`; `docs/ON_DEVICE_QA.md`; `docs/USER_MANUAL.md` | NOT RUN | NOT RUN | NOT RUN |
| `docs/AUDIT_FINDINGS_2026-11-10.md`; `docs/THIRD_PARTY_PROVENANCE.md` | NOT RUN | NOT RUN | NOT RUN |
| Relevant source, tests, Gradle, Android and TV workflows | NOT RUN | NOT RUN | NOT RUN |

## C. Worker ownership reservation — at least two active (pending Hermes confirmation)

No actual Hermes worker has been launched by this documentation update. All task reservations below are **PROPOSED / NOT STARTED**, contingent on fresh source and PR audit. **Two active workers are the minimum, not a maximum.** Any third or later worker must receive its own disjoint task, worktree and file ownership. Hermes coordinator does not count. Follow the native cron specification in `docs/HERMES_INTERNAL_CRON.md`.

| Slot | Candidate objective | File ownership **once reserved** | Forbidden overlap | Status |
|---|---|---|---|---|
| Hermes integrator | Audit, coordinate, review/merge; shared ledger/docs | `AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/**`, Gradle settings/build files; shared changes only after serialized coordination | Do not independently modify an agent-owned implementation file mid-task | **RUNNING** (runtime live; ledger update on `hermes/ledger-update`) |
| Worker A | Actual two-tile selectable-audio Media3 path, or closest unblocked enabling slice | Explicit assigned paths under `app/src/main/**`; exact related `core/src/**` / test files only as reserved | `scripts/**`, `.github/workflows/**`, all shared docs | **ACTIVE** — `sa-0-6375ec58`, PLAY-01+MV-01, worktree `/TunerWork/workerA`, branch `hermes/worker-a-playback` |
| Worker B | Real compiled TV/emulator acceptance & memory instrumentation, filling coverage gaps | `scripts/**`, `.github/workflows/**`, `app/src/androidTest/**` and distinct QA fixtures | `app/src/main/**`, shared doc/Gradle files | **ACTIVE** — `sa-1-c8728573`, QA-02+PERF-01, worktree `/TunerWork/workerB`, branch `hermes/worker-b-qa` |

Do not create worker branches until these are reconciled with existing work. If Worker A must edit `MainActivity`, that file is owned exclusively by A until it finishes; Hermes may integrate it only after it is released. Worker B must never change it concurrently. Integrator alone updates this ledger, and may serialize the corresponding doc commit.

## D. Task queue — candidate priorities only

1. **P0 — FAILING TEST FIRST:** If any current build/test/TV job is red, reproduce and fix the actual root cause with regression proof. Not assigned until latest CI is inspected.
2. **P0 — UI acceptance:** Current TV guide loaded/open heights and remote navigation; compile/rerun genuine Android TV view-tree and screenshot tests, verify selection and focus. Existing PR #97/#98 and `docs/ON_DEVICE_QA.md` must be read before duplicating.
3. **P0 — Actual playback/multiview:** Existing `MultiviewSession` core policies do not prove two tile players; inspect Android code. If missing, implement lean two-tile experience with single audible tile, capacity limits and lifecycle, supported by emulator tests.
4. **P0 — DVR, safety, recovery:** Find untested foreground service interruption, real alarm rearm/reboot and storage/fault seams; prefer safe loopback/instrumented fixtures over code duplication.
5. **P1 — Performance:** 25k M3U / 250k XMLTV, real 1/2 GiB emulator/TV resource telemetry, frame rates, channel switching and long low-CPU soak. Capture comparable before/after PSS and CPU samples.
6. **P1 — UX/accessibility/branding:** Preserve approved Classic Cable design, test FocusRing/remote/touch, integrate actual owner-provided branding; don't invent assets or redesign outside assigned scope.
7. **P2 — Documentation/release:** Reconcile status/docs with exact-SHA tests, licenses, signing and actual remaining hardware blockers.

Select only tasks absent from current `main`, not in open PRs or other reserved worktrees; create measurable acceptance before editing.

## E. Required checkpoint entry template

Use this template per completed/blocked task, append it below, and commit/push after reconciliation:

### Task ID / owner / status
- Objective and user-visible effect:
- Original problem / verified root cause:
- Source base SHA / branch / worktree / owned paths:
- Source changes and architectural rationale:
- Alternative rejected / failure history:
- RAM, CPU, storage, network and battery implications:
- Tests added, command, runner, device, API, seed:
- **PASS/FAIL/BLOCKED/NOT RUN/UNVERIFIED** for each check, exact log/evidence:
- Commit SHA, PR link, GitHub Actions run URL at that SHA:
- Integration merge SHA and exact-main CI verification:
- Security/provenance review:
- Open limitations, rollback, next assigned task:
- Last known good terminal commands + restart action:

### OPS-01 / OPS-02 / Hermes integrator / PASS (runtime setup), 2026-11-23T05:59Z
- Objective and user-visible effect: stand up the TunerOS autonomous development runtime — two real disjoint workers plus one native five-minute watchdog — per PR #118.
- Original problem / verified root cause: handoff docs were documentation-only and explicitly recorded worker count and cron as NOT RUN; no runtime existed.
- Source base SHA / branch / worktree / owned paths: `main` `a523c2e43d198ee752f7c4f03f9f7853356ffb30`; PR #118 head `1fe3134a19c219441f1f8d89ab36da58e36c91b9`; integrator worktree `C:/Users/X1/TunerWork/integrator` (`hermes/ledger-update`, tracks PR branch), owned paths `docs/**` and local `.hermes/tunos/**`.
- Source changes and architectural rationale: no product code changed. Registered one native cron job `0f160f15b5a4` (`*/5 * * * *`, enabled, workdir TunerOS, continuity on, deliver local) with a self-contained payload implementing the seven checks from `HERMES_INTERNAL_CRON.md`. Reserved disjoint ownership in `.hermes/tunos/ownership.md`; launched two `delegate_task` children with separate worktrees/branches at the reconciled base SHA.
- Alternative rejected / failure history: OS Task Scheduler / crontab / external watchdog explicitly rejected per AGENTS.md. Manual cron `run` reported "already running" (no duplicate launched). Local build attempt not possible (no JDK/SDK on PATH) — recorded BLOCKED, not PASS.
- RAM, CPU, storage, network and battery implications: negligible local overhead; two worker contexts and one low-frequency scheduler tick; no emulators launched merely to satisfy worker count.
- Tests added, command, runner, device, API, seed: no new product tests from integrator. Verification commands: `cronjob_manage list/create/update/run`, `delegate_task list`, `gh pr checks 118 -R DevTunaSoft/TunerOS`, `gh run list -R DevTunaSoft/TunerOS --limit 8`, `git worktree list`.
- **PASS** — cron job listed enabled at `*/5 * * * *` with real ID `0f160f15b5a4`; first real tick wrote a checkpoint at `2026-11-23T05:59:26Z` reporting 2 active workers. **PASS** — `delegate_task list` shows both children `status: running` with fresh transcripts. **PASS** — PR #118's four checks green at head `1fe3134` (self-tests run 45119109785, TV checks 45119109796, two builds). **BLOCKED** — local JDK/Android SDK unavailable. **UNVERIFIED** — physical device acceptance, auto-recovery exercise, durability across runtime restart.
- Commit SHA, PR link, GitHub Actions run URL at that SHA: worker commits pending; PR #118 open, NOT merged; CI URLs above.
- Integration merge SHA and exact-main CI verification: NOT DONE — merges require explicit owner authorization.
- Security/provenance review: no credentials, provider URLs, DRM keys, recordings or private data logged; `.hermes/` excluded from git.
- Open limitations, rollback, next assigned task: restore by re-running the cron payload and the two worker briefs; next: verify worker commits and exact-SHA CI, review, then hold merges pending authorization.
- Last known good terminal commands + restart action: `cronjob_manage list`; `git -C C:/Users/X1/TunerOS worktree list`; `delegate_task list`; re-create workers from `workers.json` if the session restarts.

### MV-01/PLAY-01 — Worker A (`sa-0-6375ec58`) / PASS (policy), BLOCKED (AGP) / 2026-11-23T06:10Z
- Objective: genuine bounded two-tile Media3 path with exactly one audible tile; verify select-to-play first.
- Root cause / audit: `MainActivity` has exactly one `player: ExoPlayer?` and one `playerView`; D-pad OK and touch both funnel into `MainActivity.play(Channel)`. No two-tile path existed. Classic Cable activation itself had no defect.
- Base SHA / branch / worktree: `a523c2e` → commit `aeb0032d941a7009e3b92e75945d6ccf97db57e0`, `hermes/worker-a-playback`, `/TunerWork/workerA`. Owned paths only.
- Changes: new `multiview/MultiviewTileController.kt` (max 2 live players, secondary volume 0f, focus promotes muted tile and releases prior primary, pause releases secondary, stop releases both, idempotent/bounded), `ExoPlayerMultiviewPlayer.kt` (Media3 bridge), `MultiviewTileControllerTest.kt` (10 JUnit 4 tests). No MainActivity edits, no new services.
- Failure history: two direct-compile/test failures (`setVolume(F)V` JVM signature clash; assertion counted created vs live players). Both were test defects, fixed at root; final `JUnitCore` run `OK (10 tests)`, exit 0.
- Status: **PASS** direct JVM JUnit run; **BLOCKED** `./gradlew :app:testDebugUnitTest` (no JDK at first, then `SDK location not found`); **UNVERIFIED** AGP compilation of the Media3 bridge and device two-tile playback.
- Integrator follow-up: added `testImplementation("junit:junit:4.13.2")` on the same branch at `92d8355358e7e08bba15169f29f5f616a0fe2ffe`; PR #119 opened. Exact-SHA CI at `92d8355`: build PASS (run 45122566156, 1m11s; run 45122566172, 1m20s), TV device checks PASS (45122566172, 3m29s); Android-35 self-tests still PENDING at last check.
- Next: MV-02 MainActivity wiring, assigned to Worker A2 (`sa-0-29326fe6`, `hermes/worker-a2-mv02`).

### QA-02/PERF-01 — Worker B (`sa-1-c8728573`) / PASS (local) / 2026-11-23T06:10Z
- Objective: deterministic large-EPG import/no-EPG/search/future-only/memory gate plus bounded CI coverage.
- Base SHA / branch / worktree: `a523c2e` → commit `174c9cc763807bb487751ed1e622222320d65e0b`, `hermes/worker-b-qa`, `/TunerWork/workerB`.
- Changes: `scripts/generate_qa_epg_fixture.py` (seed 20261123, 25,000 channels/250,000 programmes, 128 MiB cap, `.invalid` URLs only), `scripts/QaFixtureCheck.java` (existing core entry points only), `scripts/qa_epg_fixture_check.sh`, `qa/fixtures/core-classpath.init.gradle.kts`, `qa/fixtures/README.md`, one bounded step in existing `android.yml` (no new workflow/emulator).
- Failure history: first check FAIL (`future window on qa.ch.1 returned 9, expected 10`); root cause was the generator's first slot ending at the anchor, fixed by shifting `first` to `ANCHOR_EPOCH - SLOT_SECONDS`; assertion unchanged. Rerun PASS.
- Status: **PASS** all seven local gates including determinism (identical SHA over two seeded runs), 25,000/250,000 counts, hide-no-EPG 24,750 visible/250 hidden, name search, future-only window, peak heap 149,679,616 B < 402,653,184 B limit. **PASS** `bash -n`, YAML parse, `git diff --check`. **PASS** TV checks and builds at exact SHA `174c9cc` after one infrastructure-flake failure (details below). **UNVERIFIED** Linux RSS gate (Windows returns -1), Android-35 self-tests at last check, physical device.
- Integrator follow-up: PR #120 opened at `174c9cc`; no PR checks appeared initially, so both workflows were dispatched at the exact SHA. First TV run 45122677349 **FAIL** in the device-check step (`adb: device offline` repeatedly during a 42,670 ms emulator boot, then `adb` exit 255 at teardown) — infrastructure race, not a product assertion. Rerun 45122761338 at the **same SHA** PASS (TV device checks 2m50s, build 42s; other build 1m24s). Android-35 self-tests still PENDING at last check. One equivalent infrastructure failure only, so no strategy change required yet; a second identical failure must trigger root-cause work on the emulator boot/teardown wait per AGENTS.md.
- Next: QA-03 switching-soak harness, assigned to Worker B2 (`sa-1-bc1bf8b9`, `hermes/worker-b2-qa03`).

## F. Failed-attempt / anti-loop record

No Hermes worker attempts have occurred as part of the handoff creation. Do not invent failures or successful tests. After two equivalent failures, record both logs, root-cause investigation and changed strategy here. After two no-progress cycles move to a separate unblocked task.

## G. Session recovery (run automatically; do not ask owner to paste commands)

1. Re-open `AGENTS.md` and `docs/HERMES_AUTONOMOUS_TAKEOVER.md`; finish all mandatory full-document reading.
2. Detect local checkout / preserve `git status`; enumerate worktrees and agents, `git fetch --all --prune`, then re-audit remote `main`, PRs, issues and latest Actions results.
3. Reconcile every ledger line with exact remote commits; never repeat already merged work.
4. Restore at least two distinct active worker sessions/worktrees/branches if supported and necessary; reserve disjoint file paths and select measurable, unblocked tasks.
5. Use PC or GitHub to build/test. Confirm every claimed PASS with exact-SHA command/results. Commit/push validated work and open/merge PRs under existing repository protections.
6. Serialize this ledger and related release/QA document updates. Continue autonomously during the active session. If blocked, record exact failure, recoverable artifact/worktree and next executable step.

**Important:** Do not equate a new handoff PR, a proposed worker, or a passed synthetic test with a finished product or real Android hardware acceptance.

## H. Internal cron and liveness ledger

- **Required platform:** Hermes-native recurring scheduler only; `*/5 * * * *` (every five minutes). **External system cron/Windows Task Scheduler is not authorized as a substitute** for this requirement.
- **Cron job ID:** `0f160f15b5a4` — name "TunerOS five-minute autonomous continuity", schedule `*/5 * * * *`, repeat forever, enabled, `workdir=C:\Users\X1\TunerOS`, `deliver=local`, `continuity=true`. Registered via Hermes's native `cronjob_manage` tool (gateway PID 7200 alive at check). No OS cron / Task Scheduler / external watchdog used.
- **Native mechanism:** Hermes internal cron tool `cronjob_manage` (create/run/list/update), verified in the live job list (3 jobs total; the two others are unrelated Canopy/Cinnamon jobs). One TunerOS job only.
- **Real native cron invocation evidence:** PASS — first real scheduled fire recorded at `2026-11-23T05:59:26Z` in local `.hermes/tunos/checkpoint.md` (job ID, UTC, worker count, CI and blockers). A manual `run` was issued at ~05:57Z and the scheduler reported the job already in flight; no duplicate worker/branch/worktree was created.
- **Observed real active worker count:** 2 (minimum met, Hermes coordinator excluded) at `05:59:26Z`, confirmed from live `delegate_task` registry and fresh transcript mtimes: Worker A `sa-0-6375ec58`, task PLAY-01+MV-01, worktree `C:/Users/X1/TunerWork/workerA`, branch `hermes/worker-a-playback`; Worker B `sa-1-c8728573`, task QA-02+PERF-01, worktree `C:/Users/X1/TunerWork/workerB`, branch `hermes/worker-b-qa`. Both rooted at base SHA `a523c2e43d198ee752f7c4f03f9f7853356ffb30`; disjoint file ownership per `ownership.md`.
- **Auto-recovery test:** NOT RUN — no worker has stalled/terminated yet. The liveness rule (transcript mtime <=15 min for `delegate_task_child`; heartbeat + live PID for `durable_process`) is installed in the job payload and ready to exercise.
- **Durability across session/runtime restart:** UNVERIFIED — `delegate_task` children are process-local and die with the parent process; ticks fire only while the Hermes gateway/scheduler is alive. The cron job can create durable detached `hermes chat -q` workers with `terminal(background=true, persist_on_release=true)` when restoration is needed. No 24/7 uptime claim.
- **Local build environment:** BLOCKED for Android build/emulator — no JDK/Android SDK on this shell's PATH at check time; Worker B provisioning JDK 17 locally. GitHub Actions CI is available and green.
- **Configuration/acceptance instructions:** `docs/HERMES_INTERNAL_CRON.md`.
- **Actionable priority roadmap:** `docs/HERMES_EXECUTION_ROADMAP.md`.

When Hermes installs cron, replace these lines with the real job ID, native command/API used, verified firing timestamps and results, active worker task IDs/worktrees, and genuine remaining blockers. Avoid a commit every five minutes just to report a heartbeat; durable ledger writes should correspond to meaningful work or changes in blocker state. Never assert agents or cron are active from the mere presence of these documentation files.
