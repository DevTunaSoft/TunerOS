# QA-57 — bounded host gate for recording-library label composition

Slice selected: bounded composition of recycled recording-library row labels from a
finite scratch catalog. Each label must carry the title, state, a single protection
marker when present, an unknown-time fallback when the start is absent, a deterministic
MiB size with one decimal, and a not-playable marker unless the state is COMPLETED and
a location is present. The location and failure columns are evidence only and must
never appear in a label. No product, core, Gradle, workflow or shared document is
changed.

## Audit and duplicate exclusion (performed before authoring)

Cross-ref audit of `scripts/qa*/`, `qa/fixtures/qa*/`, `app/src/androidTest/**`,
`core/src/test/**` and `app/src/test/**` over all local and remote refs:

- refs scanned: 380 (`git branch -a`, `for-each-ref refs/heads refs/remotes`);
- QA/androidTest corpus: 10,612,728 bytes, sha256
  `322fdde1be1e9211fbe7c4d3c968be928135e81a2e6b5c1e09897c278a906fa8`;
- JVM unit-test corpus: 115,023,651 bytes, sha256
  `e70344588d979fe75eb952fa8b12ce9309422880c9115d72068a1b2e01b75fea`;
- zero-hit keywords across both corpora: `recording library`, `library ordering`,
  `library label`, `label composition`, `Unknown time`, `PROTECTED marker`,
  `Not playable label`, `row label`, `catalog dedupe`, `catalog ordering`,
  `newest-first`;
- `RecordingLibraryAdapter` has zero references in both corpora, and the zero-hit
  keywords above cover the label rule it implements.

Named exclusions verified from each branch's own diff and fixture set:

- candidate 1 (reminder/notification admission, rearm, future-only) rejected:
  QA-14 owns `reminders.tsv` / `fired.tsv` and QA-04 owns exact-alarm startup rearm;
- candidate 2 (DVR series-rule conflict across overlaps) rejected:
  QA-08 owns `rules.tsv` / `schedules.tsv` with conflict and overlap checks, and
  QA-37 owns series-rule replacement;
- QA-25 owns catalog paging, started-epoch ordering and field preservation, not
  row-label composition; QA-12 owns crash/partial-write recovery; QA-17 owns SAF
  location lifetime; QA-49 owns SAF tree admission; QA-27 owns EPG theme tokens;
- QA-56 (display-number mapping), QA-55 (XMLTV genre tokens), QA-54 (favorite
  stable key), QA-53 (M3U hygiene), QA-52 (player reuse), QA-38..QA-50, QA-04,
  QA-08, QA-14 and QA-37 each own a different bounded slice;
- QA-51 has no branch content at the pinned base.

No equivalent gate exists, so the selected slice is not a duplicate.

## Commands (offline, JDK 17, no new dependency)

```
bash scripts/qa57/run.sh generate    # regenerate the pinned scratch fixture
bash scripts/qa57/run.sh verify      # byte proof, 12 RED controls, restore proof, GREEN
bash scripts/qa57/run.sh gate-only   # unchanged GREEN alone
bash scripts/qa57/agp_compile.sh     # offline AGP compile and assembly attempt
```

## Gate design

`app/src/androidTest/java/com/devtunasoft/tuneros/qa57/Qa57Contract.kt` is the single
framework-free contract, compiled both into the host gate and into the instrumented
mirror. `scripts/qa57/Qa57HostMain.kt` is the host entry. The fixture is 256 bounded
rows at a 256-row cap, ten hex-encoded corrupt row variants, eight golden label cases,
pinned dimensions and a sha256 manifest. Regeneration is byte-identical. The GREEN
report carries 17 named checks, no field values and no fixture prose. It runs under
`-Xmx96m` and reports heap maximum and thread count before and after.

Negative controls are scratch-only. Each mutation must exit nonzero with its exact
first code: `BYTE_IDENTITY`, `MANIFEST_CONSISTENT`, `PINNED_DIGEST`, `HEADER_STRICT`,
`ROW_BOUNDS`, `CAP_BINDING`, `CORRUPT_FAIL_CLOSED`, `PRIVACY_SCAN`, `PLAYABLE_GATE`,
`PROTECTED_MARKER`, `UNKNOWN_TIME` and `SIZE_ROUNDING`. The last four mutate a scratch
copy of the contract source; the original is never edited. Owned-source and fixture
tree hashes are compared before and after every control.

Diagnostics carry check ids, counts and digests only. The detector scans all fixture
bytes and all emitted lines for a run-time derived marker and for generic address,
credential, code and payload patterns. No address, access value, code or payload text
is present in any fixture or report. Time rendering uses a synthetic token instead of
a device locale formatter, so the mirror is deterministic on every host.

## Status vocabulary

Host verification and compilation are recorded from real command output in the worker
heartbeat. Device execution without an attached authorized target is `BLOCKED / NOT
RUN`; a compiled test APK is never presented as device acceptance. The instrumented
manifest is owned by several unmerged QA branches; this branch adds only its own
instrumentation entry and leaves the merge decision to the integrator.
