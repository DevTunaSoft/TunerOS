QA-32 - host-only deterministic gate for MAP-04 storage and CORE-49 URI redaction
=================================================================================

Owned paths only: scripts/qa32/**, qa/fixtures/qa32/**. No product source, test
sources, Gradle, manifest, workflow or shared document is changed. No PR is opened
or merged by this worker. Base worktree SHA: 8e51c56a0b675372873de9f92b21b6bf3aeb9590.

Pinned inputs (full SHA; a moving branch name is never trusted)
---------------------------------------------------------------
MAP04      9d27d9e25ec3a72a5d7bade03d513546d95a9b3a  PR 210, storage fix
MAP04_BASE 126f9635a08a37f5793260ca5528417a064d2730  parent of MAP04
CORE49     85563da9200eff57c5c34ab271d6ecc67db4ae18  PR 208, URI redaction
CORE49_BASE 6c8fed5cd3348f73ca1b13e502181211be9900a8 parent of CORE49

Audit boundary (no duplication)
-------------------------------
QA-29 (worker-b38) gates DVR terminal-state transitions with its own host gate and
androidTest contract. QA-30 (worker-b39) owns compiled TV-guide instrumentation.
QA-31 (worker-b40) is a host-only static-needle gate over DVR-03/REL-01 rearms.
A cross-branch grep over every sibling worker-b* QA script/fixture tree returned
zero hits for GuideMappingStore, HlsCaptureEngine, MALFORMED_RESOURCE, MAP-04,
CORE-49, guide_mappings or canary markers. This gate therefore covers different
invariants: the real GuideMappingStore load/save semantics and the real private
HlsCaptureEngine.resolveHttp error path at their exact fix commits.

What is verified
----------------
MAP-04: the real GuideMappingStore.kt from the pinned commit is compiled on the JVM
with host-only Android/JSON seams. A private reflection handle reaches the real
JsonCodec, so save/load exercise the shipped org.json-shaped encode/decode path.
- json.get() keeps a valid mapping when a sibling entry is a number or boolean/null;
  there is no json.getString(channelId) call in load;
- malformed and empty payloads load as empty maps without throwing;
- save emits the pinned on-disk JSON bytes with the same storage key and put/toString
  contract as the pre-fix parent, so the format is unchanged;
- a deliberately stale key is still written and read back: load never writes or
  removes, and save performs no implicit pruning.
CORE-49: the real HlsCaptureEngine.kt plus its pure-Kotlin dependencies are compiled
from the pinned commit. A private reflection handle invokes resolveHttp with a
runtime-derived synthetic canary embedded in a malformed base URI and in a malformed
relative URI. Both failures must be IllegalArgumentException with the pinned generic
message and no canary text; the parent commit has no such guard.

Commands
--------
  bash -n scripts/qa32/run.sh
  python3 -m py_compile scripts/qa32/mutate.py
  bash scripts/qa32/run.sh gate-only    pinned inputs, GREEN checks only
  bash scripts/qa32/run.sh verify       GREEN, one scratch mutation per gate, RED,
                                        byte-identical restore, cached GREEN rerun

Bound and privacy
-----------------
Only the declared pinned git objects are read (11 files, each <= 131072 bytes and
4000 lines). No emulator, no network, no product edit. The canary token is derived
at runtime from a fixed seed and is never written to source, fixtures or report.
The fixtures contain no URL, scheme literal or credential; the scheme is assembled
from a char list inside the probe. The report carries check ids, counts and hashes
only. A generic sensitive-pattern scan plus an explicit canary scan run over every
emitted report line and every committed fixture.

Mutation controls
-----------------
Each mutant is a scratch copy of exactly one declared pinned source file:
- MAP-04: json.get(channelId) -> json.getString(channelId); first failure must be
  M_NONSTRING_RECOVERY;
- CORE-49: throw IllegalArgumentException(MALFORMED_RESOURCE) ->
  throw IllegalArgumentException(relative); first failure must be
  C_CANARY_ABSENT_RELATIVE.
Both mutations are restored from the original bytes and the SHA-256 of each scratch
copy is asserted equal to its original. Device execution is not claimed.

Reading record
--------------
Read for this task: AGENTS.md, HERMES_EXECUTION_ROADMAP.md, HERMES_INTERNAL_CRON.md
and HERMES_WORK_LEDGER.md (shared scratch doc copies), CLAUDE.md, the
deterministic-qa-gates skill and its byte-exact-fixtures reference, and the complete
MAP-04 and CORE-49 commit diffs. No full engineering-book reading is claimed.
