# Hermes-native cron: five-minute TunerOS continuity check

**Required schedule:** `*/5 * * * *` (every five minutes, continuously while Hermes's internal scheduler is running).  
**Owner:** Hermes's **internal scheduler/cron facility**.  
**Do not** install Windows Task Scheduler, `crontab`, a system service, a Python watchdog or an external automation for this requirement.

Hermes must locate and use its *actual available internal cron tool/command/API*. Do not guess a slash-command, package, API endpoint or cron capability: inspect Hermes documentation and its configured tools. Register **one** recurring job, reuse/update it if already present, record the real job ID and verify an actual firing. This file contains the job's instruction payload, not proof of an installed job.

## Exact job instruction for Hermes

> TunerOS five-minute autonomous continuity watchdog.
>
> 1. Check the live active Hermes worker/session registry (not just tab titles or Git branches). Count workers actually coding, testing, reviewing or performing measurable engineering tasks; **Hermes coordinator does not count**. Target **at least two** active independent workers at every check. More are allowed if tasks do not overlap and resources permit.
> 2. For each worker check session/process liveness, allocated task ID, non-overlapping owned paths, worktree/branch, last tangible progress, blocking tests, CI and completion status. An idle, disconnected, terminated, unresponsive or looping worker is not 'active'. Use supported progress indicators rather than inventing heartbeats.
> 3. If fewer than two are active, immediately restore/restart workers using Hermes's real agent launching facilities, only after reconciling surviving state and worktree ownership. Select the next unblocked independent tasks from `docs/HERMES_EXECUTION_ROADMAP.md` and the live work ledger. Do not spawn conflicting duplicate sessions or restart a live task from scratch. Do not fabricate a worker when the platform cannot create one.
> 4. If two or more are active, ensure each is producing useful changes/test evidence, not indefinite unchanged analysis. After two identical failures investigate root cause and change strategy. If completed, dispatch the next task immediately; **do not wait for the next cron tick** to replenish.
> 5. Audit outstanding PR/CI outcomes at exact head SHA, prioritize red build/test repair, safe code merge and queued P0 defects. Avoid duplicate GitHub workflows, repetitive no-op commits and resource-exhausting parallel emulators. Use PC and GitHub for testing where authorized.
> 6. Record a bounded checkpoint in the single ledger with: UTC time of check, internal cron job ID, number of truly active workers, worker task IDs and unique worktrees, corrective action, blockers, last verified SHA and next tasks. Log failures to meet the minimum honestly using **PASS/FAIL/BLOCKED/NOT RUN/UNVERIFIED**. Avoid repetitive Git commits every five minutes solely for heartbeats: keep ephemeral monitor state local and commit meaningful ledger changes with verified work.
> 7. Keep TunerOS developing under the original low-RAM, clean-code, autonomous-QA and privacy standards; do not request routine user interaction.
>
> **Important limits:** Cron ticks only happen while the internal scheduler is alive. A shut-down PC, disconnected agent service, expired authorization, or unavailable tool may prevent checks. The job cannot override platform access restrictions. If the native cron feature does not exist or cannot run while the session is idle, report BLOCKED clearly and do **not** substitute OS Task Scheduler or pretend it is installed.

## Registration acceptance

- **NOT RUN until Hermes executes it:** discover the actual native cron mechanism and read its documented schedule format.
- Check existing scheduled jobs; if a TunerOS continuity job is present, reconcile/update rather than duplicate.
- Create exactly one job for `*/5 * * * *` with the complete instruction payload above (or its semantically identical native structure).
- Confirm the scheduler's job list shows the actual job ID, five-minute interval, enabled status and next expected firing.
- Observe at least one actual tick and resulting worker registry and ledger record.
- Simulate or safely observe a worker finishing/exiting; confirm recovery without duplicate edits or destructive resets.
- Record whether the internal scheduler keeps firing across session transitions and PC idle; do not claim persistence without observation.

## Minimal five-minute report format

```text
TunerOS internal cron: PASS | FAIL | BLOCKED | UNVERIFIED
UTC:
Job ID:
Real active workers: N (minimum 2)
Agent/task/worktree:
Corrections started:
Last tested Git SHA and CI:
Blockers:
Next immediate tasks:
```

This routine is the safety net. The supervisor should replace completed workers immediately even between scheduled firings.
