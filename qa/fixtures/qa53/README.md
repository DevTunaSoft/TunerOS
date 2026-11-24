QA-53 scratch fixture. Synthetic data only; no provider address, account, PIN,
credential or recording payload appears here. Hosts use the reserved .invalid suffix.

Files:
  contract.txt    prose pinned by the privacy scan
  dimensions.tsv  counts, marker seed and pinned aggregate digest
  cases.tsv       36 bounded M3U admission cases
  corrupt.tsv     8 fail-closed input shapes
  headers.tsv     520 bounded synthetic header values, each emitted twice
  sha256.txt      per-file SHA-256 manifest

Regeneration is byte-identical from the constants in Qa53Contract; sha256.txt is
regenerated with the data files. All names are invented scratch names.
