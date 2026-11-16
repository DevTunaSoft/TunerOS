# TunerOS working agreement

This is the entry point for coding sessions in this repository. It merges four behavioural guidelines (section 2) with TunerOS's existing standards (section 1). Nothing in the existing standards is removed, weakened or reordered. Where the two could seem to conflict, section 3 states the resolution.

Detail lives in the engineering book: `docs/engineering-book/ENGINEERING_CODING_DOSSIER.md` (component contracts, test matrix, checklists), `docs/engineering-book/MASTER_HANDOFF.md` (vision, history, working standards) and `docs/engineering-book/START_HERE.md` (reading order).

## 1. Existing standards (preserved)

1. **Mission and design.** A native Kotlin Android TV and mobile IPTV app with a classic-cable feel, kept light on RAM, CPU and storage. Prefer stable, understandable Kotlin over frameworks, speculative abstractions or duplicate subsystems (dossier A).
2. **Clean, modular code.** `core/` is pure Kotlin with fast JVM tests. `app/` is the Android adapter. UI and I/O stay separate (dossier C; MASTER_HANDOFF, "Low memory, clean modular code").
3. **Memory efficiency is a hard constraint.** Bounded parsing and I/O, direct-to-disk writes, recycled UI rows, O(log n) lookups on hot paths. Performance claims need measurements, not code reading alone (dossier N; MASTER_HANDOFF).
4. **Autonomous testing.** Every behaviour change gets a test that runs without the user: JVM unit tests, debug-only in-app checks on scratch data, and the Android 35 emulator run in CI. Hardware-only conditions are reported as BLOCKED or UNVERIFIED. They are never handed to the user as manual steps (dossier O; MASTER_HANDOFF, standard 7).
5. **Thorough regression checks.** A failing behaviour gets a test that reproduces it before the fix. Regression coverage stays deterministic and bounded. Never weaken or skip a check to get green. After two identical failures, change the investigative approach (dossier O, failure triage; dossier S).
6. **Before merge** (dossier S): no duplicated feature; failure invariants stated; no unnecessary dependency; structured errors; positive, negative, boundary and interruption tests; streams, cursors and files closed in `finally` or `use`; no credentials in diagnostics; allocations and PSS compared where relevant; the exact SHA and evidence recorded.
7. **Evidence and honesty.** Report PASS, FAIL, BLOCKED, NOT RUN or UNVERIFIED with the exact commit, device and test context. Mark a check PASS only when that commit's output shows PASS. A proposed test is not a PASS (MASTER_HANDOFF, standard 6; START_HERE).
8. **Continuous development.** Keep working through verifiable steps until the task is done or a user-owned blocker is reached. Fix failing CI or a broken build first, then take the highest-priority unblocked item (MASTER_HANDOFF, step 3; dossier X).
9. **Repository workflow.** One branch per change. The pull-request run is the CI gate. Passing work reaches `main` by fast-forward once the exact head's run has zero FAIL. Never force-push. Avoid duplicate branches and PRs. Status docs cite the run that verified each claim.
10. **Privacy and data.** No M3U or XMLTV URLs, credentials or private media in the repo, logs or chat. Credentials go only through the secure prompt. Never touch the user's real recordings or database. QA uses scratch names (dossier M).
11. **Sources and attribution.** No bundled streams or third-party code without verified rights and attribution (MASTER_HANDOFF, standard 8).

## 2. Behavioural guidelines (added)

Adapted from the behavioural guidelines in the CLAUDE.md of `forrestchang/andrej-karpathy-skills`. Paraphrased. Their licence has not been checked, so confirm before quoting them verbatim elsewhere.

**2.1 Think before coding.** State assumptions explicitly. When several readings exist, name them and choose the most likely one. If a simpler approach exists, say so. Stop only for decisions that belong to the user: device or hardware evidence, provider URLs and credentials, product direction, data identity or migration, legal questions, and outward or irreversible actions. Otherwise proceed and report the assumption (dossier W).

**2.2 Simplicity first.** Build the minimum that solves the stated problem. Add no speculative features, configurability, or handling for states that cannot occur. Do not add an abstraction with a single caller. A module boundary the architecture already requires (`core/` and `app/`) is not speculative. If a solution is far longer than it needs to be, rewrite it before reporting.

**2.3 Surgical changes.** Every changed line traces to the task. Match the surrounding style; do not reformat or "improve" neighbouring code. Remove imports, variables and functions that your own change orphaned. Leave pre-existing dead code alone and report it. Exception: a broken contract on a path you are changing is in scope, and it gets a regression test.

**2.4 Goal-driven execution.** Turn each task into a check before coding: a failing test to make pass, a debug check, or an exact-commit CI run. For multi-step work, write a short plan of step, then verification. Loop until the check passes or a user-owned blocker is reached.

## 3. How the guidelines fit the existing standards

| Guideline | Resolution |
|---|---|
| "If unclear, stop and ask." | Asking is reserved for user-owned decisions (2.1). Everything else: state the assumption, proceed and report it. This keeps continuous development (standard 8) and the handoff completion rule (dossier W). |
| "No abstractions for single-use code." | Does not forbid existing module boundaries or the seams that tests need. It forbids new layers with one caller. |
| "Surgical changes." | Scope is the task. A broken contract on a path you touch is in scope and gets a regression test (standard 5). |
| "Caution over speed." | Caution applies to correctness, data safety, and irreversible or outward actions. It does not pause routine work with a verifiable check. Trivial edits use judgment. |
| "Goal-driven execution." | Strengthens autonomous testing (standard 4). Each task has a success check, and the loop continues until it passes. |
| "Simplicity first." | Never overrides the memory budget (standard 3), the module boundaries (standard 2) or the regression checks (standard 5). |

## 4. Where to find things

- `docs/engineering-book/START_HERE.md`: reading order for a takeover.
- `docs/engineering-book/ENGINEERING_CODING_DOSSIER.md`: component contracts, test matrix and checklists (sections A to Y).
- `docs/engineering-book/MASTER_HANDOFF.md`: vision, history and working standards.
- `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/ON_DEVICE_QA.md`: current status and evidence.
