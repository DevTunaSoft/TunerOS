# QA-46 — bounded HTTP identity-length completion verdict gate

Owned paths: `scripts/qa46/**`, `qa/fixtures/qa46/**`, `app/src/androidTest/**`.
No production file is edited by this task.

## Slice

`core/BodyLength.kt`: `expectedLength` (declared length plus case-insensitive
identity encoding, or `-1`) and `verifyCleanEnd` (clean end of stream is not
proof of a complete body). The host gate drives the real compiled object from
a byte-pinned fixture with 462 truth-table rows and eight corrupt rows.

This is not QA-19 (source acquisition, redirect and gzip byte cap), QA-30
(HLS 206 partial-content range), QA-15 (large EPG import), QA-43 (source
hygiene), QA-44 (timeshift ring filesystem) or QA-45 (source restoration).
Across 272 local and remote worker refs, zero QA-owned files mention
`BodyLength`; the only `Content-Length` mentions are unrelated UI
instrumentation fixtures. Core JVM tests exist, but no QA fixture gate did.

## Fixture

`qa/fixtures/qa46/` is written by `Qa46Gate generate` from fixed constants:

- `dimensions.tsv`: 462 cases, 8 corrupt rows, cap 462, 4 cycles;
- `cases.tsv`: declared length, encoding token, copied bytes, expected length
  and expected verdict per row, including `-1`, `Long.MIN_VALUE`,
  `Long.MAX_VALUE`, empty/mixed-case identity and non-identity tokens;
- `corrupt.tsv`: hex-encoded raw rows pinned to a specific first rejection
  code (truncated, wrong field count, bad long, bad token, bad verdict,
  duplicate id, non-ASCII byte, overlong row);
- `notes.txt`: fixed prose with invented labels only;
- `sha256.txt`: per-file digests; aggregate digest pinned in the gate source.

`.gitattributes` pins LF bytes. `verify` regenerates all five files into
scratch and requires byte identity with the committed fixture.

## Commands

    bash scripts/qa46/run.sh generate
    bash scripts/qa46/run.sh verify
    bash scripts/qa46/agp_compile.sh

`run.sh verify` compiles the unmodified Kotlin object with the offline K2
compiler and the zero-dependency Java gate with `javac`, runs six RED mutants
with pinned FIRST codes, proves scratch and committed bytes restore, then
runs the GREEN gate under `-Xmx64m`. Every compile and gate invocation has a
`timeout`; no network is used.

## Negative controls

| Mutant | Pinned FIRST |
|---|---|
| production identity literal changed | `EXPECT_LENGTH_TABLE` |
| production negative-length guard loosened | `VERDICT_TABLE` |
| corrupt row made parseable | `CORRUPT_REJECT` |
| one byte drifted in fixture prose | `BYTE_IDENTITY` |
| synthetic address injected into fixture prose | `PRIVACY_SCAN` |
| manifest digest drifted | `MANIFEST_MISMATCH` |

The synthetic address in the privacy mutant is assembled from character
codes at run time and exists only in scratch, never in a committed file.

## Diagnostics and device status

Diagnostics contain check ids, counts, digests and measured heap/thread
drift only. Every emitted line is scanned for address, access-value, PIN,
secret and recording-payload patterns plus a runtime-derived synthetic
marker before any fixture prose is treated as input.

The Android instrumentation source, manifest and bounded compile runner are
owned here. Offline AGP compile/package may PASS; execution has NOT RUN and
the physical-device result is UNVERIFIED without an authorized adb target.
