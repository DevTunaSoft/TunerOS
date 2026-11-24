# QA-39 — consolidated host gate over delivered QA-31..38 gates

Bounded, deterministic, host-only. Reads immutable pinned git objects only: no
emulator, no network, no product edit, no PR, no merge, no secret. Synthetic
marker names only. Owned paths: `scripts/qa39/**` and `qa/fixtures/qa39/**`.

## Pinned deliveries (full SHAs observed locally; never a moving branch name)

| Gate | Role | Delivery SHA |
|---|---|---|
| QA-31 | DVR combo | `fcabc641cb2444219dbbedfbaaf235df4668090a` |
| QA-32 | MAP-04 / CORE-49 | `cc45c4790a9aa216d5ec2fa9e38d906d2b4aa1dd` |
| QA-33 | MAP hazard marker | `a584c39df00e825bf68ff9ebae039fbc5dd6e51e` |
| QA-34 | dual hazard | `2f4aa76870dae0eb991492329132a8fdf39ea132` |
| QA-35 | GUIDE-07 | `3cfc95a64d33e197850780cc324fd0ebc356cde4` |
| QA-36 | HTTP-07 | `d8495e4af738f497e6f79e791d790c07428e1245` |
| QA-37 | FAV-06 | `9df525ad5c1b11f8625949888474b24f86967b81` |
| QA-38 | RESTORE-02 | `1da0e7079595d39741599d7a32194103fa8b9ca7` |

QA-38 was first observed at `cfba7aa0b933a96166beb362d945e1199924bf7b`
(truthful FAIL at audit pin C13 `c1d6f62b`), then superseded by the final
B45 re-pin at `1da0e707...` (C13 `fc8986725c8fa3cda4acc429266526b9a237d90d`,
30/30 PASS). Only the final SHA is pinned here.

## Audit boundary — no single-gate check duplicated

QA-29 (b38) and QA-30 (b39) were read as prior slices; they gate DVR terminal
state and bounded 206 admission. This gate never re-runs a sibling gate and
never re-derives its per-case assertions. It checks only the *consolidation*
contract: each delivery commit exists and its branch ref still resolves to it;
the complete script/fixture file set and every blob's git object id, byte length
and SHA-256 are unchanged; each sibling's own `sha256.txt` fixture manifest
agrees with its pinned bytes; each sibling's pinned refs are full-length,
distinct-per-key commit objects; declared verdict needles in each `run.sh` and
README are intact; cross-gate pins (QA-34 -> QA-31/QA-33, QA-38 -> QA-37) still
match this gate's pins. Hazard gates QA-33 and QA-34 must still declare
`MERGE-REVIEW-NEEDED`, emit bounded `HAZARD` lines and carry the pinned
`MERGE-REVIEW-NEEDED` marker text — they may not be silently downgraded to PASS.
QA-38's own truthful verdict at its final pin is PASS with one synthetic marker.

## Commands and exact observed status

```
bash -n scripts/qa39/run.sh                       PASS
python3 -m py_compile scripts/qa39/Qa39Gate.py    PASS
sha256sum -c qa/fixtures/qa39/sha256.txt          5/5 OK
bash scripts/qa39/run.sh gate-only                EXIT=0
  SUMMARY checks=60 pass=60 fail=0 first=NONE verdict=PASS delivered=8 hazard=2
bash scripts/qa39/run.sh verify                   EXIT=0
  RED      PASS rc=1 first=QA33_HAZARD_VERDICT verdict=FAIL
  RESTORE  BYTE_IDENTICAL sha256=bfe28c7d6b6575ec6cacc3de74ec62b53d592105a7bddb0b948b762590d554f2
  GREEN    PASS checks=60 pass=60 fail=0 verdict=PASS
  MUTATION PASS single-scratch-mutation-detected-and-restored
```

The single negative control rewrites one declared `gates.tsv` row in a native
scratch overlay, changing QA-33's declared verdict from MERGE-REVIEW-NEEDED to
PASS. The named `QA33_HAZARD_VERDICT` check fails first; no golden-byte or
manifest mismatch may mask it (the manifest check is emitted last). The
overlay is removed after byte-identical restoration and a clean GREEN rerun.
Original fixture bytes are hashed before and after the probe.

## Bounds and privacy

Each git object is capped at 131072 bytes; the report is capped at 120 lines and
contains ids, counts, digests and short status only. A generic sensitive-pattern
scan covers all fixture bytes and every emitted line. No M3U/XMLTV URL, token,
credential or real media name appears in code or fixtures. Exit codes: 0 PASS,
1 FAIL, 2 SETUP error. No device execution is claimed by this gate.

## Reading record

Read for this task: `AGENTS.md`, `docs_HERMES_INTERNAL_CRON.md` and
`docs_HERMES_WORK_LEDGER.md` (shared scratch copies), `CLAUDE.md`,
`docs/ROADMAP.md`, and the `deterministic-qa-gates` skill plus
`isolated-worktree-coding-worker` skill. Sibling QA-29..38 READMEs, refs,
manifests and verdict code were audited at their pinned commits. No full
engineering-book reading is claimed.
