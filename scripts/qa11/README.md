QA-11: deterministic, zero-dependency host gate for guide-state backup/restore.

Audited seams not duplicated here:
- QA-02: deterministic 25k/250k EPG fixture and bounded core-parser check.
- QA-05: guide search fixture/verifier.
- QA-07: favorites in the recycled guide plus parental PIN store, not backup/restore.
- QA-09: diagnostics gate.
- QA-10: event-log gate.

This gate owns the adjacent untested seam: TunerBackup encode/decode of favorites, guide mappings
(driving hidden-EPG visibility after restore), parental-restricted groups and restricted channels,
over a bounded scratch set. It checks byte-exact regeneration, pinned SHA-256 manifest, fail-closed
rejection of nine corrupt backup shapes, canonical round-trip, normalized restore, recycled-row bounds,
secret-free diagnostics, and six on-disk RED mutants before a GREEN run.

Commands (from repository root, JDK 17 required):
  bash scripts/qa11/run.sh generate    regenerate committed scratch fixtures
  bash scripts/qa11/run.sh verify      host gate on committed fixtures
  bash scripts/qa11/run.sh red-green   six mutants (each must fail with a pinned first check), then GREEN

The Kotlin mirror in app/src/androidTest runs on the JVM and inside the instrumented APK; its
regenerated backup digest is pinned by the host gate. Device execution requires an attached,
authorized device or emulator; without one it is BLOCKED/UNVERIFIED.
