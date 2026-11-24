# QA-42 fixture: bounded playback / EPG soak contract

Synthetic, byte-pinned inputs for the offline soak gate in `scripts/qa42/`.
No real channel, programme, stream, URL, credential, media, recording or
provider data appears in any file.

- `dimensions.tsv`: 19 pinned dimensions. 96 synthetic channels x 48
  programmes = 4,608 expanded EPG rows; 24-row pages with 16 recycled slots;
  96 soak steps over 8 header groups; 160 segment arrivals; timeshift caps of
  12 segments and 105,000 bytes; one deliberately oversized 150,000-byte
  segment.
- `markers.tsv`: eight policy markers, all `1`. The mutation probe rewrites
  `byte_budget_enforced` to `0` in a scratch copy and requires the gate to go
  RED with `FIRST=POLICY_MARKER`.
- `epg.tsv`: 96 channel rows with deterministic epoch starts and seeds. The
  gate expands them into contiguous half-open programme windows and pins their
  SHA-256 in `expectations.tsv`.
- `soak.tsv`: 96 channel-switch steps with header identity, recording flag and
  the pinned reuse/created/released/live ledger after each step.
- `segments.tsv`: 160 segment arrivals with duration, size, expected evictions,
  first evicted sequence and retained count/bytes. Both retention caps bind:
  byte evictions and count evictions both occur, and the live window peaks at
  the 12-segment cap.
- `expectations.tsv`: 19 pinned derived counts and two derived digests.
- `sha256.txt`: digests of the six data files, computed from committed bytes.

The model is an independent contract derived from the read-only core sources
(`TimeshiftWindow`, `TimeshiftCapture`, `PlayerReusePolicy`) and the sibling QA
gates; it copies no production source and is not an Android decoder test.
