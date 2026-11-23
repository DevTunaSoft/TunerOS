QA-22 - bounded HLS AES-128 IV admission and stream-decrypt gate
=================================================================

Owned paths only: scripts/qa22/**, qa/fixtures/qa22/**, app/src/androidTest/**.
No core/src, app/src/main, Gradle, workflow, document or shared manifest is changed.
No PR is opened and no merge is performed by this worker.

Audit boundary (read first)
---------------------------
Reviewed the pinned base plus sibling QA-01..QA-21 branches before choosing a slice.
- QA-01/QA-06 cover compiled TV-guide layout; QA-02/QA-15 cover large EPG fixtures and retention.
- QA-04 covers schedule durability; QA-05 guide search; QA-07 favourites and the PIN store.
- QA-08 covers DVR overlap and series rules; QA-09 doctor report privacy; QA-10 event-log aggregation.
- QA-11 backup/restore; QA-12 crash/partial-write recovery; QA-13 timeshift windows; QA-14 reminders.
- QA-15 repeated imports; QA-16 cancellable staging; QA-17 SAF destination and location lifetime.
- QA-18 corrupt doctor-evidence envelopes; QA-19 source-feed acquisition; QA-20 parental decisions.
- QA-21 self-test report publication.
not_duplicate_of: all of the above. No audited gate reads HLS AES-128 explicit-IV admission or
bounded streamed segment decryption. JVM unit tests exist for those functions, but none provides a
byte-exact scratch fixture, pinned SHA-256 manifest, RED mutation controls, privacy proof or an
instrumented mirror. QA-22 owns that gate-level slice. Its host gate compiles the real pure-Kotlin
HlsAes128 source read-only; no production file is edited.

Chosen slice
------------
Initialization-vector admission (implicit media-sequence and explicit hex), bounded AES-128-CBC
stream decryption with sink-left-open and plaintext accounting, fail-closed truncated,
wrong-key and bad-padding segments, a bounded large-segment heap/thread workload, and secret-free
diagnostics. All values are derived from a fixed seed; no feed address, access marker, personal
code, key material or media text is stored in the fixture or printed in diagnostics.

Commands (JDK 17, offline, no new dependency)
---------------------------------------------
  bash scripts/qa22/run.sh generate    regenerate pinned scratch fixtures byte-for-byte
  bash scripts/qa22/run.sh verify      RED controls + restore proof + GREEN gate + privacy scan
  bash scripts/qa22/run.sh red-green   alias for verify
  bash scripts/qa22/run.sh gate-only   GREEN gate and privacy scan without mutant controls
  bash scripts/qa22/agp_compile.sh     offline AGP compile/package when an SDK is present
  bash scripts/qa22/device_gate.sh     bounded instrumented attempt; BLOCKED without a device

RED controls (eight, each must exit nonzero with its pinned first code)
----------------------------------------------------------------------
byte-drift BYTE_IDENTITY; sha-drift MANIFEST_MISMATCH; expect-drift SEGMENT_EXPECT;
cap-shrink CAP_BINDING; iv-guard IV_REJECT; truncate-guard TRUNCATED_REJECT;
pad-guard CORRUPT_PAD_REJECT; leak-inject PRIVACY_SCAN.
Source mutants are applied only to scratch copies of HlsAes128.kt; each copy is restored from the
read-only original and hash-compared. The real production file and fixture tree are hashed before
and after the entire run.

Instrumented mirror
-------------------
Qa22Contract.kt has no Android imports and runs on the JVM; Qa22Instrumentation only writes a
bounded report and calls finish(). Device execution is BLOCKED/UNVERIFIED without an attached,
authorized adb device; a compiled APK is not a device PASS.

Pinned evidence (verified at the pushed commit)
------------------------------------------------
fixture_version 1; iv_cases 12; segments 6; large_cipher_bytes 262176.
dimensions.tsv sha256 8e7a31544396afb387f4e8de4387c5aa4febdceb01bdc70c3c63a41b14e2ee25
sha256.txt sha256 c9b892367310103e39f3af1edc901ae96ea9d86840e1ecfb9bf9dfb11273fd92
large.ct sha256 149d177cfb97a706271292143328805ee1198b17f7b01ca69a3e99bcffab0ee7
HlsAes128.kt sha256 4f4efa31d2334dc60aab64fd4b4fec03375f6e981a31b093209775467fb4ed8b
fixture tree digest b401fec721adc5d8b3547718912b5481de893b2259e0dc6b16718ab0f6c7126c
Mirror plaintext digest 4b0f6ccd3c58b310cdad1b13961fc5d88644ebfdef6204bb31ffd45d602523d2
Host gate: 18 checks PASS, 8 RED controls PASS, privacy scan PASS, production copy restore PASS.
JVM mirror: 8 checks PASS. AGP offline compile PASS; test APK 11778 bytes with instrumentation entry.
