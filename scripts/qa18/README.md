QA-18 - deterministic corrupt-evidence envelope gate
====================================================

Owned paths only: scripts/qa18/**, qa/fixtures/qa18/**, app/src/androidTest/**.
No production, core, Gradle, shared manifest, document, CI file, PR or merge is changed.

Audit boundary (read first)
---------------------------
- QA-04 and QA-14 own scheduled-recording durability and reminder/notification rearm.
- QA-13 owns timeshift ring clipping and live-window seek bounds.
- QA-12 owns DVR crash / partial-write catalog retention.
- QA-09 owns StreamDoctor/GuideDoctor *shareable report* privacy for hostile text and guide
  warnings; QA-02 owns the playback-soak failure taxonomy; SEC-01 owns the doctor decision table.
- No audited gate covers the byte-level ingestion seam before those reports are produced: a
  corrupt, truncated, over-long or digest-broken evidence envelope must be rejected fail-closed,
  and an accepted envelope must rehydrate into the real doctor reports without leaking any
  synthetic provider-like marker. QA-18 owns that gap. It does not repeat the neighbouring gates.

Chosen slice
------------
GuideDoctor/StreamDoctor privacy under corrupt evidence, specifically the bounded evidence-envelope
ingestion path. The host gate compiles the real pure-Kotlin core sources together with the gate;
no production source is edited. Device execution is not claimed.

Commands (JDK 17, offline, no new dependency)
---------------------------------------------
  export JAVA_HOME=C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2
  bash scripts/qa18/run.sh generate     regenerate pinned fixture bytes and sha256.txt
  bash scripts/qa18/run.sh verify       RED mutants, RESTORE proof, GREEN gate, JVM mirror
  bash scripts/qa18/run.sh red-green    alias for verify
  bash scripts/qa18/run.sh gate-only    GREEN gate without mutation controls
  bash scripts/qa18/agp_compile.sh      offline AGP compile/package; no device execution

Fixture contract
----------------
qa/fixtures/qa18/ holds contract.txt, dimensions.tsv, corrupt.hex (15 named corrupt variants),
envelopes.bin (352 length-delimited evidence records) and sha256.txt. The pinned envelope digest
is 9ab6745720c354b799bd6af4234605d5961c9945842e5984befb5d154a073fde. Every byte is regenerated
from fixed constants; verify regenerates in memory and compares against the committed artifact.
All identifiers are invented scratch tokens. No real feed address, access marker, credential, PIN,
DRM key or recording payload is stored or printed. Sensitive-looking markers are derived at run
time from a fixed seed; the detector scans fixture bytes and every rendered diagnostic.

Envelope: 4-byte QA18 magic, version byte, big-endian count, then records of magic, kind, code,
status, transport, content-type length/bytes, marker length/bytes and a SHA-256 digest over the
record's own fields. Strict decoding has pinned fail-closed codes for header, kind, ranges,
lengths, encoding, digest, truncation and trailing bytes. A deliberately lax decoder that ignores
the digest is the negative control proving the strict rejection is meaningful.

RED controls
------------
Five scratch fixture mutants (byte flip, bad header, truncation, record count, bad digest) and three
compiled source mutants (privacy detector, heap bound, thread bound). Each must exit nonzero with
its named FIRST code. Scratch fixture copies are re-copied from the committed fixture and verified
byte-identical by SHA-256; the gate source and committed fixture hashes are checked before and
after. Nothing is mutated in place.

Bounded execution and privacy
-----------------------------
The host JVM runs with -Xmx96m, bounded timeouts and 6 parse/rehydrate cycles on a named worker
thread that is joined before the heap/thread drift checks. Diagnostics contain check ids, counts
and digests only. The compiled Kotlin mirror has no Android imports and runs on the JVM before any
device attempt; the instrumented class only orchestrates it and writes a bounded report.

Device status
-------------
A compiled test APK is not a device PASS. Without an attached authorized device or emulator,
device execution is BLOCKED/UNVERIFIED.
