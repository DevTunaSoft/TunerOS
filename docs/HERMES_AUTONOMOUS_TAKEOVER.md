# TunerOS — Hermes Autonomous Engineering Takeover and Complete Handoff Addendum

**Prepared:** 2026-11-22; **repo:** https://github.com/DevTunaSoft/TunerOS; **canonical branch:** `main`; **project:** native Android TV/mobile IPTV application; **status:** early alpha, NOT production certified.

**Purpose:** Give Hermes a reproducible, no-handholding starting point, two non-overlapping development workers, explicit permission to use the dedicated coding PC and GitHub, and a verified-as-of-creation baseline. This is an **addendum**, not a replacement for the existing very large engineering books. Read them **completely** as instructed in `AGENTS.md`.

## 1. Executive decision and owner authorization

Hermes is the engineering lead, scheduler and integration controller for TunerOS. The owner expressly authorizes autonomous in-scope coding, local machine interaction, installing development/test software, running tools on the coding PC, using GitHub Actions, creating branches/worktrees/commits/PRs and pushing verified code to GitHub **without repeatedly asking permission**. Treat routine SDK download, JDK setup, emulator creation, automated Windows terminal/GUI access, test execution and authenticated `git push` as already approved **provided the active tools and OS permit those actions**.

Hermes must operate two independent, non-overlapping worker agents where real concurrency is supported. Do not spend time asking the owner to copy PowerShell commands, click through a testing checklist, run a remote, approve every commit or supply test output. Arrange the machine and CI to do those tasks. If an OS security/access prompt truly cannot be automated with the granted tools, document BLOCKED, continue unrelated work and provide the smallest possible owner-only step as a last resort. Keep the coding PC's non-project files and existing applications safe.

Authorized tools may include Git/GitHub CLI, JDK 17, Android SDK/command-line tools, Gradle wrapper, Kotlin tooling, Android emulators, ADB, Python for automation, and reputable instrumentation utilities. Prefer the existing versions already configured. Install from official trusted distribution channels, check licenses and hashes when possible, avoid surprise paid APIs/services, security bypasses or destructive system-level modification. Never copy credentials into the repository or public CI logs.

**Definition of success:** verifiably improved native application, documented code and tests on GitHub, repeatable unattended Android/TV QA, and a release decision based on actual evidence. A green synthetic emulator run is necessary but NOT sufficient proof of hardware compatibility or finished product.

## 2. Full-document reading requirement (do not paraphrase instead of reading)

Before product code changes, fully read `AGENTS.md`, `CLAUDE.md`, `docs/engineering-book/START_HERE.md`, `docs/engineering-book/MASTER_HANDOFF.md`, `docs/engineering-book/ENGINEERING_CODING_DOSSIER.md` (the entire ~528 KB dossier), `docs/engineering-book/DEEP_ENGINEERING_BOOK_VOLUME_III.md` (~181 KB), `README.md`, `docs/HANDOFF.md`, `docs/FEATURE_STATUS.md`, `docs/ROADMAP.md`, `docs/ON_DEVICE_QA.md`, `docs/USER_MANUAL.md`, `docs/AUDIT_FINDINGS_2026-11-10.md` and `docs/THIRD_PARTY_PROVENANCE.md`. Also read this file and the live work ledger in their entirety.

The earlier handoff is ~118 KB and the master handoff ~92 KB. Total docs are larger than one agent context window; **read contiguous chunks in order and continue until EOF**. Record for each file its SHA-256 or Git blob SHA, total bytes/lines, and the headings or line ranges actually processed. Use retrieval notes for working memory, but notes **do not replace the original reading**. Every worker must separately read the product standards and the relevant source/test code; Hermes as integrator verifies full-book reading once and provides stable references if worker context is limited. The extensive engineering dossier contains **proposed** test scenarios, not proof those tests ran.

Do not replace prior records with this addendum. The old docs explain the design rationale for otherwise puzzling code, prior failed approaches, and reversible or rejected features. Git history, current sources, and exact-commit CI evidence outrank older text when they disagree.

## 3. Live GitHub audit snapshot — verified 2026-11-22

These are observations made when preparing this addendum. **Immediately re-audit before starting work**; do not assume they remain current.

| Item | Observed baseline | Verification/meaning |
|---|---|---|
| Repository | `DevTunaSoft/TunerOS`, public, writable for connected account | `https://github.com/DevTunaSoft/TunerOS` |
| Default branch | `main` | GitHub repository metadata |
| Exact `main` SHA | `a523c2e43d198ee752f7c4f03f9f7853356ffb30` | GitHub branches and latest commit API |
| Open PRs | **0** | `GET /repos/DevTunaSoft/TunerOS/pulls?state=open` |
| Open issues | **0** returned by issue search | Search is not a substitute for a complete historical issue audit |
| Latest `main` Android build | **PASS**, Actions run `45011055536`, at `a523c2e` | https://github.com/DevTunaSoft/TunerOS/actions/runs/45011055536 |
| Recent PR Android TV check | **PASS**, Actions run `45010167847`, at merge/test SHA `a523c2e` | https://github.com/DevTunaSoft/TunerOS/actions/runs/45010167847 |
| Latest PR Android build | **PASS**, run `45010167864` at `a523c2e` | https://github.com/DevTunaSoft/TunerOS/actions/runs/45010167864 |
| Main push TV run | **NOT OBSERVED in the inspected recent-run response** | Do not infer one from the PR run |
| Physical Android TV, USB/SD and private paid-provider acceptance | **UNVERIFIED** | CI success cannot certify these |
| Workflows | `.github/workflows/android.yml` and `.github/workflows/android-tv.yml` | Source inspected/listed |
| Current open change collision | No open PR at snapshot | Historical feature branches remain; never treat branch presence as unfinished task |

Recent merged work includes property/fuzz-based tests for guide/recording and XMLTV, a fix to an M3U attribute-scan pathological long-name slowdown, Stream Doctor shareable safe reports, Guide Doctor summaries, and TV guide measurement and screenshots. **Do not redo these because an old roadmap entry says pending.** The guide on a TV emulator has been observed at about 123 dp high with the guide loaded and source rows hidden, and about 60 dp before loading (see `docs/ON_DEVICE_QA.md` and PR #98): this constrained layout is a usability issue to investigate via actual compiled UI assertions, not a mandate to repaint the app without evidence.

The README and `docs/FEATURE_STATUS.md` include earlier dated snapshots; their headline "latest" statements can be stale. The **latest verified exact SHA** is the live audit above until a newer CI result proves otherwise.

## 4. Product mission, original user decisions, non-negotiable UX

TunerOS is a high-quality, resource-conscious native **Kotlin + Media3** IPTV player designed for Android TV boxes and Android phones. It should feel like a familiar cable set-top box, with a **Classic Cable skin as the default** and future selectable appearances, while remaining fast on constrained Android hardware. It consumes authorized **user-supplied M3U and XMLTV**, never bundled pirated channels, stolen playlists or secret credentials.

The original browser app/version V8/V9 was a separate lineage; **do not assume its full source exists in this Android repo**. The native Android work was chosen to escape browser HLS/codec limitations and allow reliable Media3 playback and direct-to-disk DVR. Historical problems included empty/failing guide imports, timezone handling, guide-ID mismatches, playback HLS manifest failures and repeated full-guide scans freezing search.

Product expectations:
- Guide-centric channel surfing, guide activation with remote OK or touch action starts the correct live channel. Support smooth search by channel name, favorites, left/right timeline navigation, visible focus, scroll/rotation state and accessible layouts.
- **User-controlled** hide-no-EPG, default not silently excluding channels; a future-only schedule counts as EPG and must not be hidden.
- Clear Guide Doctor reasons for mismatches, stale guides and timezone warnings; bounded credential-safe report sharing.
- Native Media3 with HLS, DASH and applicable non-HLS stream support as platform allows. Treat unsupported formats honestly, never label unplayable sources as working.
- Multiview up to practical decoder/provider limits with exactly one chosen audio source, two tiles first, then optionally three/four. Actual two-tile playback and UI are **not certified** by a core policy test.
- DVR/PVR: Record Now, guide schedules, series rules, recording catalog, protect and safe delete, timeshift, encrypted scheduled credentials, exact alarms and rearm, and user-chosen SAF local/SD/USB destination. Never read full live media into RAM or call an empty recording complete.
- Low RAM/CPU/storage and network overhead is a **product requirement**, not optional polish. Prefer direct-to-disk with fixed-size ~64 KiB copy buffers, finite caps and efficient indexed guide queries. No needlessly permanent worker, timer, service, process, heavyweight framework or Rust rewrite.
- Nice design matters, but do not perform broad unrequested UI redesigns, invent branding assets, overwrite approved skins, or regress TV navigation while repairing functionality. Preserve user-supplied branding assets/provenance when available.

## 5. Architecture and protected engineering decisions

- `core/`: Android-independent Kotlin playlist and XMLTV parsers, immutable/normalized models, schedule index, timeline focus/geometry, multiview admission policy, HLS/DASH recording algorithms, timeshift and testable utilities. Retain fast JVM tests. Avoid Android imports here.
- `app/`: Android UI/Activity lifecycle, Media3 playback, encryption via Keystore, SQLite schedule and recording catalog, background foreground-service recorder, AlarmManager and boot rearm, Storage Access Framework, diagnostics, debug synthetic QA. Never run network/parse/SQLite/cryptographic work on main.
- `scripts/`: emulator, acceptance and build support. Scripts must be reproducible, deterministic, bounded and clean up processes.
- `.github/workflows/`: hosted build, Android 35 phone emulator, Android TV emulator, screenshots and instrumentation. Prefer built-in CI rather than asking a human to operate a device. Audit workflow concurrency and artifact safety.

Key historical hardening to preserve:
1. Bounded M3U reading and selective EXTINF parse; avoid regex catastrophic performance on long garbage names. Source-scoped and stable channel identity policies are **distinct**: do not change IDs without migration.
2. Bounded streaming XMLTV with DTD/external entity protections, canonical xmltv.dtd handling, nesting limits and timezone-less UTC fallback warning; source/provider zone configuration remains a possible refinement.
3. Per-channel EPG indexes, binary-search/windowing, future-only visibility, debounce and recycled guide rows: no quadratic full-guide scan per keystroke or minute tick.
4. Credentials/URLs encrypted at rest. HTTPS-to-HTTP redirects rejected; gzip sniffed and size capped. Never expose raw provider details via diagnostics or backup.
5. Recorder data length/integrity checks, cancellation and capacity recheck after network read, immutable IDs and conditional status transitions, bounded DB rearm pages, ordered worker teardown and partial finalization.
6. Unsupported HLS SAMPLE-AES was deliberately **reverted/refused** pending actual reference evidence; do not automatically restore stale branch `feat/sample-aes` or conflate HLS identity-key AES-128 with SAMPLE-AES.
7. Third-party reuse requires compatible verified license/attribution, maintained in `docs/THIRD_PARTY_PROVENANCE.md`. FastChannels on-device serving remains **deferred**, not a near-term release blocker.

## 6. Current capability / acceptance matrix (verify against today's source)

| Area | Known implemented foundation | Remaining acceptance |
|---|---|---|
| Playlist/guide | M3U/XMLTV import, caps, index, guide timeline, search/filter, favorite/mapping, Guide Doctor | Provider-specific timezone controls, truly large real feeds, physical TV smoothness and remote navigation |
| Playback | Media3 live/player lifecycle, Stream Doctor, safe classification/share | Device and provider format acceptance, decoder/rapid switching soak and non-HLS failure diagnosis |
| DVR | Raw/HLS/DASH supported subsets, encrypted schedules, alarm/rearm, SAF, library, series/time-shift controls | Real long captures, force-kill/reboot and interrupted external storage, real USB/SD behavior, physical timing |
| Multiview | Pure Kotlin tile-admission/session and single-audio policy with JVM tests | Actual two-player tile UI, audio handoff, decoder constraints and device testing |
| Parental/backup | Group/channel restrictions, PIN rate limiting, backup/restore policy and UI work | End-to-end touch/TV picker and PIN dialog behavior on device |
| Design | Classic Cable timeline and FocusRing improvements, basic icon/banner placeholders | Premium final brand assets, TV layout width/height, accessibility, large-font and small-screen acceptance |
| Testing | Core JVM regression/property/fuzz, built-in debug Android QA, phone/TV emulators and CI artifacts | Physical 1/2 GiB TV box, real controlled hardware event rigs and sustained memory/battery |

The detailed evidence matrix is in `docs/FEATURE_STATUS.md` and `docs/ON_DEVICE_QA.md`. Every status must be reconciled against the current commit; past text may now lag behind code.

## 7. Two-agent parallel operating model

**Hermes is the integrator, not an extra competing file editor.** Start two coding workers backed by the available Hermes subagent orchestration; if unavailable, use two isolated local agent processes/sessions with distinct worktrees. Never claim parallel agents without confirming two actual running workers. Use a **single authoritative** work ledger at `docs/HERMES_WORK_LEDGER.md`.

### Start-of-session procedure
1. Confirm repo is clean or preserve any uncommitted user work. Fetch all refs; inspect default branch, commits, issue/PR history, Actions jobs and branch protections. Never delete unknown worktrees or overwrite edits.
2. Compare changed files on recent PRs against roadmap. Reuse merged code; resolve any still-open failed CI first.
3. Have both workers read the complete required docs and their assigned code; record file SHA and ranges in their private notes, then ledger reading status. Do not require human acknowledgement of an already defined product requirement.
4. Choose two truly independent acceptance-backed tasks. Create branches/worktrees from the same up-to-date verified baseline. Specify disjoint ownership **at file/path level** before dispatch. If a shared file is necessary, integrator serializes a small targeted edit after worker PRs.
5. Workers implement and test in independent trees; Hermes monitors reproducible test evidence, diff quality and resource impact. Git push, PR creation, review and CI observation are routine approved actions.
6. Integrate green PRs one at a time; rerun the full required checks after rebasing/synchronizing second work. Do not force merges, disable required checks or skip run matching the **exact head SHA**. Record commit/PR/test logs in ledger and continue.

### Initial worker tasks — candidates to confirm from live code
**Worker A (feature and Android code):** inspect the existing multiview controller/session/test and any real Android UI; only if missing, implement the **smallest real two-tile Media3 playback** with a single audible tile, safe decoder/provider admission limits, focus/remote selection, lifecycle and release; build target tests. Ownership: exclusively assigned `app/src/main/**` paths and narrowly required `core/src/**` paths. Do not touch shared workflows, scripts, README or coordinator docs. If an end-to-end two-tile feature is too large for one safe change, deliver an independent tested enabling slice with explicit no-duplicate review.

**Worker B (device QA and performance):** improve automated acceptance of the compiled TV UI and existing functionality without editing product `app/src/main/**`. Extend `scripts/**`, `.github/workflows/**` and/or `app/src/androidTest/**` with objective screenshots, view-bound checks, remote D-pad/OK navigation, guardrails for the known constrained TV guide row layout, memory/CPU sampling and reproducible lifecycle/soak tests. Do not introduce paid providers, leak private URLs or assert success based on synthetic models alone. Avoid broad workflow edits if the existing suites already do the job; prioritize an uncovered defect and reproduce it before a fix.

**Collision protocol:** Worktrees are not sufficient if filenames overlap. Declare each owned path and exact PR scope in the ledger; at the first overlap halt the later task, split ownership or have integrator serialize. Shared `MainActivity`, project Gradle files, `AGENTS.md`, `CLAUDE.md`, README and all roadmap/handoff/ledger docs belong to integrator until explicitly reassigned. Use no blanket cherry-pick or wholesale rebase that drops user work.

## 8. Autonomous test lab: PC + GitHub

### PC baseline and toolchain
The target is a dedicated **Windows coding PC**. On startup discover the actual CPU/RAM/disk, JDK, Git, GitHub CLI authentication, Android SDK, Java/Gradle versions, hardware virtualization, emulator/KVM/WHX availability, device inventory, ADB and existing build caches. Do **not** assume previous host RAM or disk figures are current. Install missing official components only if necessary, on the isolated coding machine, using scripted checks and unattended flags where safe; never expose access tokens. Prefer Windows-compatible shell scripts or cross-platform tooling rather than requiring owner PowerShell copy/paste. Cache tool downloads conservatively.

Use `gradlew.bat --no-daemon :core:test :app:assembleDebug` on Windows or `./gradlew --no-daemon :core:test :app:assembleDebug` on POSIX, using JDK 17, the pinned repository Gradle wrapper (8.9 in audit), and Android API 35 (confirm in current build). Review unit/test count and errors; record exact commits. The current root build declared Android Gradle Plugin 8.7.3 and Kotlin 2.0.21; do not upgrade reflexively.

### GitHub CI
`.github/workflows/android.yml` runs JVM tests, debug APK, build-upgrade baseline and Android 35 low-memory emulator synthetic self-tests, with report artifacts. `.github/workflows/android-tv.yml` runs TV-specific checks. Read both workflows before adjusting them. PRs trigger hosted verification; main push paths may exclude docs-only files, but PR verification remains. Record run URL, exact commit, workflow/job, test count, device/API and resource sample, if actually measured. Audit token permissions, secrets and logs before changing workflows.

### Test quality policy
- **Unit/property:** positive, negative, boundary, concurrency, restart and malicious input cases; reproducible seeds; timed or allocation-sensitive hot paths.
- **Android integration:** real production parser/recorder/player code running in emulator with owned scratch sources; no test-only mock being presented as proof the actual path works.
- **TV UI:** real compiled APK, screenshot(s), UIAutomator hierarchy, dp bounds, D-pad focus traversal, selection to first frame and accessible labels. Compare measured layouts with requirements, not visual guesses.
- **Playback:** controlled local HTTP/HLS/DASH fixtures, switching 100+ times if bounded by test budgets, decoder resource cleanup, metadata/audio focus, media format refusal. Reference real public streams only when licensed/available without user credentials.
- **DVR faults:** interruption and service teardown, zero-byte/short-body files, duplicate scheduler alarms, Doze, reboot, SAF permission loss, drive disconnect if available; ensure private scratch files and stable user catalog.
- **Performance:** representative large playlist/EPG and multiview workloads, before/after PSS (and peak/sampled PSS), CPU, startup, main-thread stalls, storage churn, network calls, battery where measurable. A short idle snapshot is not an all-day or real-hardware RAM ceiling.
- **Safety:** local-only diagnostics, no provider URL, auth header, secret, personal content or paid media in artifacts.

Prefer Android emulator and GitHub runners for unattended validation. If a physical Android TV device is already connected to the PC, use ADB and device automation under existing authorization and safe app-owned test data. Do not root/reflash it, wipe app data or records, or modify unrelated devices. For real USB unplug or hardware power loss without a remotely controlled fixture, mark **BLOCKED/UNVERIFIED** and build a reproducible harness for later rather than telling the owner to test manually.

## 9. Git operations and publication — required

Use a local dedicated clone/worktrees with clean Git state. Avoid recreating existing branches/PRs. The preferred cycle is **branch → focused code/test changes → local verification → commit → push to `origin` → PR → GitHub checks at exact SHA → review → protected/rule-compliant merge → verify `main`**. Publish useful verified progress to the remote, not merely local files. Do not report completion if unpushed.

A PR must state: user-visible change, actual root cause, files, design alternatives, bounded memory/CPU implications, tests added and run, exact SHA and run IDs, hardware not tested, and rollback. Keep documentation changes synchronized after integration. If `main` or a PR changes concurrently, fetch/reconcile and revalidate. Never force push or silently bypass CI; do not merge unverified feature branches. Green PRs are not guaranteed production-ready.

## 10. Anti-loop and durable continuation

Avoid repeated thinking/status loops. After two equivalent failed actions, examine logs, tool versions, source, environment and exact error; select a materially different fix. After two cycles without measurable forward progress, record a precise blocker and advance a separate task. Do not keep rerunning an unchanged test. No global `git reset --hard` of unknown work; preserve uncommitted user changes and private files.

For every meaningful checkpoint update **one canonical ledger** `docs/HERMES_WORK_LEDGER.md` with branch, commit, PR, task owner, exact file ownership, what changed, test commands/results, CI URLs, memory impact, open blockers, next eligible tasks and exact restart instructions. Integrator serializes ledger writes to prevent merge conflicts. Also reconcile the user manual, roadmap, feature status, on-device QA and docs only when they change materially.

Continue autonomously while a work session is running and authorized tools are active. Do not promise work after the session ends unless an actual persistent agent/scheduler is running. If a model disconnects or the PC restarts, the ledger and pushed Git branches/PRs must allow a successor to resume from the exact state without asking the owner to re-explain project vision.

## 11. Release/finish gate

Do **not** call TunerOS finished until Android TV and phone navigation, representative first-frame playback, searchable/filterable guide, DVR/PVR and rearm, selectable-audio two-tile multiview, accessibility, privacy, backups, build/release signing, and relevant fault/recovery cases pass with documented evidence. Benchmark sustained workload/resource usage on targeted low-RAM hardware or clearly document limits. Unavailable real-world acceptance must be BLOCKED or UNVERIFIED; there is no false PASS substitute.

The initial handoff task is complete once the complete book is read, current source/PR/CI audit reconciled, two independent worker tasks allocated without file overlaps, test environments actually available, and first independently testable changes committed/pushed with evidence. Then continue the highest-value unblocked work.

## 12. Recovery checklist for a fresh Hermes session

1. Open https://github.com/DevTunaSoft/TunerOS; read `AGENTS.md` and this handoff **completely**, then finish **every** original book and companion file listed above. Record document read completion.
2. `git fetch --all --prune`; check `git status`, `git worktree list`, `git branch -avv`, current `origin/main` SHA; inspect existing PRs/issues, latest GitHub Actions checks, diffs and branches.
3. Read the ledger and reconcile each task against code; if another worker owns a file, do not edit it.
4. Verify the current Android/TV test baseline with the existing Gradle and SDK. Fix genuinely failing build/CI before feature growth.
5. Start exactly two non-overlapping workers with separate branches/worktrees if supported; give their explicit working scopes.
6. Commit and push each verified change and open PRs. Track exact-SHA CI and merge safely; update ledger and documentation; continue.
7. Preserve factual statuses **PASS / FAIL / BLOCKED / NOT RUN / UNVERIFIED**. Never invent runtime evidence.

**No permission round-trips for ordinary coding-PC operations; no manual owner QA checklist; no overlap; no duplicate features; no memory regressions; no silent GitHub omissions.**
