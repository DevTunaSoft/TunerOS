# QA-49 pinned scratch fixture

Generated only by `scripts/qa49/run.sh generate`; the verify path never writes here.

- `contract.txt` — bounded contract prose (501 bytes before the latest regeneration).
- `dimensions.tsv` — fixed seed, family/case/cycle caps and the pinned cases digest.
- `cases.tsv` — strict 12-column TSV, 9 families x 8 cases = 72 rows plus one exact header.
- `sha256.txt` — `sha256  filename` lines for the three data files.
- `.gitattributes` — `* -text`, so LF bytes survive checkout with `core.autocrlf=true`.

All names are synthetic (`qa49-*`). No provider address, account name, PIN, DRM key,
recording payload or real volume identifier is present. The privacy marker is derived at
run time from the seed and is never stored in these files.

Pinned digests are printed by the gate at every run; the current values are in
`dimensions.tsv` and `sha256.txt`. The committed tree digest and source digest are
recorded in the worker heartbeat RESTORE lines and the commit message.
