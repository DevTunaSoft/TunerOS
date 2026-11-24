QA-59: bounded DASH listed-live refresh and grace verdict matrix.

Slice
  Real production core/DashCaptureEngine.kt, driven through its public record() entry with a
  deterministic fake DashCaptureSource, fake clock and synthetic scratch manifests. No QA-01..QA-58
  gate references DashCaptureEngine; the base JVM unit tests cover adjacent behaviour but there is
  no host fixture gate. Production is compiled unmodified into the gate.

Modes
  scripts/qa59/run.sh generate    regenerate qa/fixtures/qa59 byte for byte
  scripts/qa59/run.sh verify      byte identity, RED controls, restoration proof, GREEN gate
  scripts/qa59/run.sh gate-only   GREEN gate without mutation controls
  scripts/qa59/agp_compile.sh     offline AGP compile and assemble of the instrumented mirror

Fixture
  rows.tsv           12 bounded cases, one per family, with pinned verdict fingerprints
  dimensions.tsv     row/field/family caps and a pinned mirror digest
  corrupt.txt        four named malformed blocks, each rejected by a strict parser
  report-copy.txt    scratch prose used by the privacy scan
  sha256.txt         one sha256 per data file, LF separated

Checks (all emitted as tab-delimited CHECK lines)
  HEADER_STRICT, ROW_BOUNDS, MANIFEST_CONSISTENT, BYTE_IDENTITY, PINNED_DIGEST, CAP_BINDING,
  CORRUPT_FAIL_CLOSED, CONTRACT_VERDICTS, BOUNDARY_GRACE, BOUNDARY_STALL,
  BOUNDARY_REFRESH_CLAMP, NO_SILENT_GAP, PRIVACY_SCAN, MEM_STABLE, THREAD_STABLE.

  BOUNDARY_GRACE pins 120 half-second retries for a 30-second live segment (a 60-second grace),
  BOUNDARY_STALL pins three ten-second idle refreshes at the 30-second limit, and
  BOUNDARY_REFRESH_CLAMP pins 1000 ms and 10000 ms clamping. These are literal expectations,
  not values read from the constants under test.

Negative controls (each must exit nonzero and print its pinned FIRST code)
  byte-drift -> MANIFEST_CONSISTENT, byte-flip-remanifested -> BYTE_IDENTITY,
  digest-drift -> PINNED_DIGEST, cap-drift -> CAP_BINDING,
  verdict-drift -> CONTRACT_VERDICTS, privacy-leak -> PRIVACY_SCAN,
  corrupt-relax -> CORRUPT_FAIL_CLOSED, no-gap-golden -> NO_SILENT_GAP,
  stale-write -> BOUNDARY_STALL.

Runtime
  JVM gate: -Xmx96m, timeout 180 s, no network. All scratch paths are under the Hermes scratch dir.
  The gate prints only check ids, counts and hashes. The run-time token is derived from a seed and
  is never written to source, fixture or report.

Device
  Instrumentation source and manifest live in app/src/androidTest under package
  com.devtunasoft.tuneros.qa59. The root androidTest manifest is shared with sibling workers; the
  integrator must merge their instrumentation entries. No authorized device is declared, so
  instrumented execution is BLOCKED / NOT RUN regardless of a successful offline assemble.
