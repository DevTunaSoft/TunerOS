# QA-35 — GUIDE-07 recycled-row D-pad window-shift host gate

Bounded, deterministic, host-only static gate over **pinned git objects**. No emulator,
no network, no product edit, no secret. Synthetic sentinel marker only.

## Pinned refs (`qa/fixtures/qa35/refs.tsv`)
- `BASE` `a523c2e` — reconciled main base.
- `PRE` `0029ffc` — GUIDE-07 parent, before the fix.
- `FIX` `62308fa` — C7 GUIDE-07 fix (PR 214).
- `UX01` `70cd7c1` — A3 UX-01 sub-glyph guard commit.

## What is checked (43 fixed rows in `expectations.tsv` + 9 structural checks = 52)
- Baseline at `PRE`: no pending direction, no focus policy, no `requestWindowShift`;
  the old per-`View` `pendingWindowShiftDirection` state is present.
- Direction stored on `CableGuideAdapter`, keyed by `focusedChannelId`:
  `pendingFocusShiftDirection`, guarded `requestWindowShift`, callback and key-listener
  rewiring, pure `GuideRowFocusPolicy.shiftDirectionForBind`, focus-owner key and
  invalidation on channel change.
- `ProgramTimelineView.bind` takes defaulted `windowShiftDirection: Int = 0` and uses it;
  no per-`View` retained direction or previous-channel field remains; draw-time text
  region guard is intact.
- `GuideRecycledRowFocusTest.kt` is new in `FIX` with exactly five `@Test` regressions
  and all five named methods.
- A3 UX-01 sub-glyph guard verified on the pinned `UX01` object (`drawsTitle`,
  `>= glyphWidthPx`, `minTitleGlyphWidthPx`, guard call). A structural check proves
  GUIDE-07's `ProgramTimelineView` diff touches no draw-region line, so integration
  cannot clobber that guard.
- Fixture SHA-256, refs distinct and common base, exact fix file set, one bounded
  sentinel marker, no production drift, no sensitive patterns.

## Commands
```
bash -n scripts/qa35/run.sh
python3 -m py_compile scripts/qa35/mutate.py
bash scripts/qa35/run.sh gate-only     # prints CHECK lines + SUMMARY, exit 0 PASS / 1 FAIL
bash scripts/qa35/run.sh verify        # RED mutated overlay -> RESTORED -> GREEN
```
`verify` mutates only a scratch copy of `FIX:CableGuideAdapter.kt`
(`windowShiftDirection = shiftDirection,` -> `windowShiftDirection = 0,`), requires
RED exit 1 with `first=A_PASS_TO_BIND`, byte-identical restore, then RESTORED and
GREEN both exit 0 with `verdict=PASS`.
