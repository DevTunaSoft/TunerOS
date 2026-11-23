QA-24 deterministic bounded EPG episode/season gate
==================================================

Slice
-----
Bounded EPG episode/season metadata normalization and chronological ordering over a
large bounded scratch fixture. Base: a523c2e43d198ee752f7c4f03f9f7853356ffb30.
Owned paths only: scripts/qa24/**, qa/fixtures/qa24/**, app/src/androidTest/**.

Audit (before writing any gate code)
------------------------------------
23 sibling QA branches were enumerated (QA-01 through QA-23 plus the earlier QA-02
worker). Their gate sources and 12 tracked status/roadmap docs were searched for
season/episode normalization and chronological ordering: zero gate-source hits.
Neighbours that looked similar but cover different behaviour:
  QA-01 TV guide layout and D-pad acceptance (UI, not normalization)
  QA-02 / PERF-01 large EPG fixture size and core parser bounds (no metadata)
  QA-05 guide search text fixture (no season/episode fields)
  QA-06 large Classic Cable EPG gate (scale, not episode/season ordering)
  QA-08 DVR overlap and series-rule expansion (recording, not EPG rows)
  QA-15 repeated EPG import retention (retained state, not row metadata)
  QA-16 cancellable guide-import staging (interruption, not ordering)
  QA-23 bounded DASH sidx admission (segment indexes, not EPG rows)
chosen_slice: episode-season-normalization-chronological-order
not_duplicate_of: QA-01,QA-02,QA-05,QA-06,QA-08,QA-15,QA-16,QA-23

Assumption
----------
"Empty/single/malformed input fail-closed" is read as: empty is refused with a
pinned code, a single well-formed row is the minimal accepted bound and must not
crash or invent ordering, and every malformed variant is refused with its own
pinned code. A single row is not treated as corrupt.

Fixture
-------
All bytes are generated from constants by Qa24Gate.generate and pinned by
sha256.txt plus the digests in dimensions.tsv. rows.tsv holds exactly 10000 rows
(the cap binds). Derived coverage: unknown=3021, clipped=3062, prefixed=3129,
padded=3066. cases.tsv holds 20 normalization cases. The six bad_*.tsv files
carry malformed variants; empty.tsv and single.tsv carry the boundary inputs.
.gitattributes disables line-ending conversion so LF bytes stay byte-identical.

Normalization contract: trim, collapse whitespace, lowercase title key, reject
control characters; season/episode accept an optional S/E prefix and ASCII digits
only, remove leading zeros, reject a significant width above 9 as overflow, clip
values above 9999 to 9999, and map empty to unknown (-1), which sorts last.
Order is (start_epoch, season, episode, title_key, id).

Diagnostics contain check ids, counts and digests only. No address-like string,
password, code marker or recording payload is emitted or stored.

Verification status
-------------------
Host RED: 7/7 mutants nonzero with pinned first codes (see scripts/qa24/README.md).
Host GREEN: 38 PASS / 0 FAIL under -Xmx96m at the base SHA before commit.
Kotlin mirror JVM: 32 PASS / 0 FAIL under -Xmx96m.
AGP offline compile: PASS, test APK 22838 bytes, instrumentation tag verified.
Device execution: BLOCKED / UNVERIFIED (no authorized attached device or emulator
was present; `adb devices` listed none). A compiled APK is not a device PASS.
