# QA-56 — bounded host gate for channel display-number mapping

Slice selected: bounded channel-number / display-number mapping preservation under
duplicate, blank and out-of-range numbers. The gate admits a finite scratch channel
table and checks that a valid number stays with its first claimant, that duplicate,
blank and out-of-range rows stay unmapped, and that every prefix of the table agrees
with the full assignment. No product, core, Gradle, workflow or shared document is
changed. All fixture values are invented.

## Audit and duplicate exclusion (performed before authoring)

Cross-ref audit of `scripts/qa*/`, `qa/fixtures/qa*/`, `app/src/androidTest/**`,
`core/src/test/**` and `app/src/test/**` over all local and remote refs:

- refs scanned: 431 (`git branch -a` / `for-each-ref refs/heads refs/remotes`);
- refs with QA-path blobs: 280; distinct blobs read: 853;
- audit corpus: 5,339,650 bytes, sha256
  `290a608574a5e74e87e0f5c35b4a44dd0bda496166803c4e18def9d05ca55604`;
- zero-hit keywords across the whole corpus: `tvg-chno`, `channel-number`,
  `display-number`, `channelNumber`, `chno`, `duplicate number`, `blank number`,
  `out-of-range number`, `conflict resolution`;
- cross-ref production grep for the same symbols over `core/src/main` and
  `app/src/main` on every local head: zero hits. There is no product mapping
  implementation at the base SHA, so this gate verifies the scratch contract and
  its compiled mirror rather than claiming a product-code PASS.

Named exclusions verified from their own branch diffs: QA-55 (XMLTV category/genre
token admission), QA-54 (favourite stable key), QA-53 (M3U metadata hygiene),
QA-52 (player reuse), QA-38..QA-50, QA-04 (alarm rearm), QA-14 (reminder admission)
and QA-08 / QA-37 (series-rule expansion, DVR overlap and replacement). QA-08's own
fixture set contains `rules.tsv`, `programs.tsv` and `schedules.tsv` and its README
names overlap windows and series expansion; QA-37 audits series replacement at a
pinned commit; QA-14 has its own reminder/firing ledger. None admits channel
display numbers. Slice 1 is therefore the only candidate with no equivalent gate.

## Commands (offline, JDK 17, no new dependency)

```
bash scripts/qa56/run.sh generate    # regenerate the pinned scratch fixture
bash scripts/qa56/run.sh verify      # byte proof, 11 RED controls, restore proof, GREEN
bash scripts/qa56/run.sh gate-only   # unchanged GREEN alone
bash scripts/qa56/agp_compile.sh     # offline AGP compile and assembly attempt
```

## Gate design

`app/src/androidTest/java/com/devtunasoft/tuneros/qa56/Qa56Contract.kt` is the single
framework-free contract, compiled both into the host gate and into the instrumented
mirror. `scripts/qa56/Qa56HostMain.kt` is the host entry. The fixture is 512 bounded
rows (cap 512), ten hex-encoded corrupt row variants, pinned dimensions and a
sha256 manifest. Regeneration is byte-identical. The GREEN report carries 16 named
checks, no field values and no fixture prose. It runs under `-Xmx96m` and reports
heap maximum and thread count before and after.

Negative controls are scratch-only. Each mutation is copied, given a focus code and
required to exit nonzero with that exact first code: `BYTE_IDENTITY`,
`MANIFEST_CONSISTENT`, `PINNED_DIGEST`, `HEADER_STRICT`, `ROW_BOUNDS`, `CAP_BINDING`,
`CORRUPT_FAIL_CLOSED`, `PRIVACY_SCAN`, `DUPLICATE_FIRST_WINS`, `BLANK_REJECT` and
`RANGE_REJECT`. The three last controls mutate a scratch copy of the contract
source; the original is never edited. Owned-source and fixture tree hashes are
compared before and after every control.

Diagnostics carry check ids, counts and digests only. The detector scans all fixture
bytes and all emitted lines for a run-time derived marker and for generic address,
access-value, code and payload patterns. No address, access value, code or payload
text is present in any fixture or report.

## Status vocabulary

Host verification and compilation are recorded from real command output in the
worker heartbeat. Device execution without an attached authorized target is
`BLOCKED / NOT RUN`; a compiled test APK is never presented as device acceptance.
