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
| Hermes integrator | Audit, coordinate, review/merge; shared ledger/docs | `AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/**`, Gradle settings/build files; shared changes only after serialized coordination | Do not independently modify an agent-owned implementation file mid-task | NOT RUN |
| Worker A | Actual two-tile selectable-audio Media3 path, or closest unblocked enabling slice | Explicit assigned paths under `app/src/main/**`; exact related `core/src/**` / test files only as reserved | `scripts/**`, `.github/workflows/**`, all shared docs | NOT RUN |
| Worker B | Real compiled TV/emulator acceptance & memory instrumentation, filling coverage gaps | `scripts/**`, `.github/workflows/**`, `app/src/androidTest/**` and distinct QA fixtures | `app/src/main/**`, shared doc/Gradle files | NOT RUN |

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
- **Cron job ID:** NOT RUN / not yet installed by Hermes.
- **Real native cron invocation evidence:** NOT RUN.
- **Observed real active worker count:** NOT RUN (target >= 2, Hermes coordinator excluded).
- **Auto-recovery test:** NOT RUN.
- **Durability across session/runtime restart:** UNVERIFIED.
- **Configuration/acceptance instructions:** `docs/HERMES_INTERNAL_CRON.md`.
- **Actionable priority roadmap:** `docs/HERMES_EXECUTION_ROADMAP.md`.

When Hermes installs cron, replace these lines with the real job ID, native command/API used, verified firing timestamps and results, active worker task IDs/worktrees, and genuine remaining blockers. Avoid a commit every five minutes just to report a heartbeat; durable ledger writes should correspond to meaningful work or changes in blocker state. Never assert agents or cron are active from the mere presence of these documentation files.
