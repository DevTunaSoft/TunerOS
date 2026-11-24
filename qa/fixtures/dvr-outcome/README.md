# QA-41 fixture: dvr-outcome policy matrix

Synthetic, byte-pinned inputs for the offline recording-outcome gate in
`scripts/qa41/`. No real recording, provider, URL, credential or media data.

- `cases.tsv`: 20 tab-separated rows across seven families — completed,
  zero-byte, cancelled, storage-fault, double-finalize and epoch-lost-stop
  (the seventh matrix entry, cancellation-epoch-lost-STOP, is the epoch family
  with the shared cancellation flag reset to 0).
- `markers.tsv`: seven policy markers; value `1` means the invariant is in
  force. The mutation probe rewrites `stop_epoch_persists_across_start` to `0`
  in a scratch copy and requires the gate to go RED with `FIRST=POLICY_MARKER`.
- `expectations.tsv`: pinned case, family, state and marker counts.
- `sha256.txt`: digests of the three data files, computed from the committed
  bytes. The gate verifies them in normal mode and skips that structural check
  only in mutant mode so the named semantic failure is not masked.

The decision model is derived from the read-only policy sources on sibling
branches (A4 DVR-01/02 outcome and storage policies, C23 DVR-06 cancellation
epoch, baseline core zero-byte final state). This fixture does not copy or
modify any production source; it is an independent contract the gate evaluates.
