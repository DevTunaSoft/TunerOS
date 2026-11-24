# QA-54 fixture — bounded favorite stable-key admission

All bytes are produced by `Qa54Contract.generate` from fixed constants; no randomness, no
network, no real media. `verify` regenerates in memory and compares bytes; it never writes
into this directory. The manifest pins SHA-256 of the four data files only. `contract.txt`
holds dimensions plus the aggregate digest of those data bytes; the manifest digest is not
pinned inside the contract (that comparison would be circular).

- `channels.tsv` — 32 synthetic channels (`c0000`..`c0031`) with a stable key. Five channels
  have an empty stable key (fallback to id) and three share `kshared`.
- `cases.tsv` — 24 TOGGLE rows (blank target, add, remove, duplicate keys, shared key,
  unknown key) and 24 FILTER rows (off, empty favorites, shared key, empty-key fallback,
  unknown key, off-with-favorites).
- `corrupt.tsv` — 8 adversarial channel rows with pinned strict-parser rejection reasons.
  Its payloads deliberately violate row rules; it is excluded from the benign line-length cap.
- `report-copy.txt` — benign scratch report text used by the privacy detector.
- `sha256.txt` — data-file manifest; `contract.txt` — dimensions and mirror digest.

Pinned mirror digest: `a02b0e0506c504f7e95b580ccd0622662b1ada82eabab71da28ced6b1b9416ae`.
No provider URL, credential, PIN or recording payload text appears in any fixture file.
