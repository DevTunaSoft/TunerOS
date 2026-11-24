# QA-56 scratch fixture

Five byte-pinned files generated solely from constants in `Qa56Contract.kt`:

- `channels.tsv` — 512 rows (cap 512) with stable scratch ids, raw display numbers
  and reusable scratch labels; blank, whitespace, duplicate and out-of-range rows
  are deliberate;
- `corrupt.txt` — ten hex-encoded malformed rows, each rejected by strict row
  admission;
- `dimensions.tsv` — pinned counts and the assignment digest;
- `report-copy.txt` — a short diagnostics sample carrying ids, counts and digests;
- `sha256.txt` — SHA-256 of the four data files.

`bash scripts/qa56/run.sh generate` reproduces every byte. All names are invented;
no real channel, address, access value, code or media payload is represented.
