# TunerOS — Mandatory instructions for Hermes and all coding agents

This is a project-level instruction file. Its authority supplements, and does not replace or weaken, `CLAUDE.md`, the existing engineering book, the privacy rules, CI requirements, and the product's low-resource design.

## First action: mandatory full takeover reading

Before changing any product code, every new orchestrator/agent must read **the complete contents** of these files, in this order, chunking large documents by headings or line ranges until the end of each file. Merely listing, previewing, searching, skimming, or summarizing a document does **not** satisfy the reading requirement.

1. `AGENTS.md` (this file) and `CLAUDE.md`
2. `docs/HERMES_AUTONOMOUS_TAKEOVER.md` and `docs/HERMES_WORK_LEDGER.md`
3. `docs/engineering-book/START_HERE.md`
4. `docs/engineering-book/MASTER_HANDOFF.md` — read both the initial and expanded volumes
5. `docs/engineering-book/ENGINEERING_CODING_DOSSIER.md` — all sections and test proposals
6. `docs/engineering-book/DEEP_ENGINEERING_BOOK_VOLUME_III.md` — all chapters
7. `README.md`, `docs/HANDOFF.md`, `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/ON_DEVICE_QA.md`, `docs/USER_MANUAL.md`, `docs/AUDIT_FINDINGS_2026-11-10.md`, and `docs/THIRD_PARTY_PROVENANCE.md`
8. The relevant production source, tests, build configuration, emulator scripts and GitHub workflows for the assigned tasks.

If a document exceeds context limits, process it in sequential contiguous chunks and record the last completed section; resume until every section is consumed. Do not silently substitute a partial read. If a document has disappeared, mark it BLOCKED and use Git history to recover it; do not claim completion.

After reading, compare the documents with **actual current code and GitHub state**, because older reports contain historical snapshots. The **source tree plus exact-SHA test evidence** outrank stale feature-status text. In particular, do not interpret 300 proposed tests as 300 completed tests.

## Autonomous development and dedicated-PC authority

This is a coding-only PC. The owner has standing authorization for Hermes to use available local computer/terminal/GUI tools, install and configure required free or already licensed SDKs, JDKs, Gradle, Android SDK components, emulator images, build/test dependencies, Git utilities, and test automation; run builds and tests on the PC or GitHub Actions; create worktrees, branches and PRs; commit and push verified project work. **No repetitive approval requests** for routine in-scope engineering, installations, local tests, or GitHub commits/pushes. The tool/OS must actually grant access; instruction text cannot bypass a permission prompt or manufacture PC access.

Do not acquire new paid subscriptions, submit credentials, disclose private data, disable OS security, force-push, rewrite shared history, remove real media or databases, make destructive machine-wide changes, bypass protected branch rules, or publish secrets. These are outside the standing approval. Install from reputable official sources and verify checksums/signatures when available. Preserve installed tools and user files. If elevation or an OS approval is genuinely required, minimize it; perform other independent work instead of waiting.

## At-least-two-agent orchestration (hard requirement)

Hermes is the **single integrator/coordinator**. Maintain **at least two truly active independent coding/testing worker agents** whenever Hermes's supported runtime is operating with access and unblocked tasks. The Hermes coordinator does not count. Add more workers for genuinely independent tasks when the available PC and CI resources permit. Each has its own Git worktree and branch rooted at a reconciled `main` commit. Assign non-overlapping task ownership and explicit file-path scopes in `docs/HERMES_WORK_LEDGER.md` before either worker starts. Reserve shared files (README, roadmap, handoff, ledger, build config and primary Activity) to the integrator unless the ledger grants temporary exclusive ownership. No two workers modify the same file or coordinate by force-pushing.

If true concurrent agents are unavailable, do not falsely report two running agents or pretend that serial work meets the two-active-worker floor. Use supported isolated worker processes/sessions if available, otherwise record BLOCKED while performing any independent feasible engineering. Prevent task overlap, duplicate work and excess emulator use.

Initial candidate division, subject to a fresh code/PR audit:
- **Worker A — Android playback/multiview:** product Kotlin implementation under `app/src/main/**` and specifically assigned `core/**` or app test paths as needed. Focus first on a genuine two-tile selectable-audio flow, playback lifecycle, decoder limits, and targeted automated tests. May edit the Activity only with exclusive ownership. Do not invent unsupported device acceptance.
- **Worker B — Android/TV autonomous QA and performance:** `scripts/**`, `.github/workflows/**`, `app/src/androidTest/**`, and new isolated testing fixtures. Focus first on the actual compiled TV guide layout, D-pad/OK selection, two-tile behavior where available, 1/2 GiB RAM sampling, reboots/lifecycle, and bounded diagnostics. Do **not** edit `app/src/main/**` while Worker A owns it.

If either candidate is already implemented, superseded, blocked, or overlaps active work, select the next unblocked independent roadmap task instead. Integrator owns cross-module coordination, review and integration.

## Hermes internal five-minute cron (mandatory)

Install **one** Hermes-native internal recurring job on the schedule `*/5 * * * *` (every five minutes). Its full job payload, acceptance checks and limits are in `docs/HERMES_INTERNAL_CRON.md`. The job must inspect the **real** active agent/session registry, verify at least two workers are running and producing evidence, repair/reassign failed or finished work using existing worktrees without conflict, review CI blockers, and record truthful checkpoint state. Do not use Windows Task Scheduler, OS crontab or a separate external watchdog for this request. Discover the actual Hermes cron interface and verify real execution before saying installed. The five-minute job is a backstop; replenish workers immediately on completion instead of waiting for the next tick.

The prioritized, testable execution plan is `docs/HERMES_EXECUTION_ROADMAP.md`. The existing `docs/ROADMAP.md` remains the historical decision and engineering roadmap; never discard it.

## Engineering invariants

- Kotlin-first, native Android TV/mobile app. `core/` remains Android-free; `app/` owns platform integration. Keep Classic Cable the default, user-controlled hide-no-EPG, searchable guide, select-to-play, safe DVR, and original product vision.
- Prefer simple, modular, clean code with extremely low RAM/CPU/storage use. Bounded streaming parsers, indexed EPG, recycled rows, bounded direct-to-disk recording; no unbounded buffers, redundant polling, needless services, or speculative Rust rewrite.
- No accidental regressions of DRM/unsupported-format fail-closed behavior; SAMPLE-AES was deliberately reverted pending reference evidence.
- No provider credentials, private URLs, DRM keys, M3U/XMLTV feeds, secrets, or recording payloads in code, git, log, CI artifacts or messages.
- No tests that require the owner to operate a remote or manually verify visuals. Build automated emulators, instrumentation, screenshots and objective layout/focus assertions where feasible. Unsupported physical scenarios remain BLOCKED/UNVERIFIED.
- Follow upstream license and provenance checks for borrowed code. Do not silently bundle streams or paid content.

## Verification, commits, continuation

For each task: reproduce/define acceptance; modify only owned files; run unit/build/instrumentation and relevant fuzz/fault/memory checks; fix root causes; reread diff; push branch; obtain **exact SHA** CI evidence; merge only when required checks are green and branch protections satisfied; update docs and authoritative ledger. Status vocabulary: **PASS, FAIL, BLOCKED, NOT RUN, UNVERIFIED**. On two equivalent failures or two fruitless cycles, investigate and switch approach. Never weaken tests merely to get green.

No fabricated progress, phantom commits, unrun tests, or background-work promises. When work is complete, automatically pick the next non-overlapping unblocked roadmap task within the active session; stop only at a real access, owner-only, safety, or technical blocker. Leave full restoration commands in `docs/HERMES_WORK_LEDGER.md` before stopping. Do not create recursive workflow triggers or duplicate PRs.
