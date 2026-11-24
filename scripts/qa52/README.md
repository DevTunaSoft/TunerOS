QA-52 bounded host gate: cross-provider player reuse header isolation.

Scope: core/PlayerReusePolicy.kt canReuse. Eight synthetic current header profiles,
seven next profiles and two recording-state values make a bounded 112-row matrix.
The verdict must come from the real compiled production function, not a copied model.

Audit finding: NO_EQUIVALENT_GATE. Across 283 worker refs and a 10,593,117-byte QA
corpus the strings canReuse and nextHeaders had zero hits in QA gate bodies. QA-03
models only a string header key in an instrumented soak with recording state fixed
to false; the base JVM unit test is not a deterministic host fixture gate. QA-01,
QA-04, QA-07, QA-09, QA-19, QA-20, QA-22, QA-25, QA-30, QA-41, QA-43, QA-44,
QA-45, QA-46, QA-47, QA-48, QA-49, QA-50 and QA-51 are explicitly excluded.

Modes:
  bash scripts/qa52/run.sh generate   regenerate committed fixtures byte-for-byte
  bash scripts/qa52/run.sh verify     regen proof, 10 RED mutants, restore proof, GREEN
  bash scripts/qa52/run.sh gate-only  GREEN gate only
  bash scripts/qa52/run.sh            same as verify

The gate runs with a 96 MiB Java heap and no worker threads. It checks strict fixture
parsing, fail-closed corrupt rows, pinned digests, regeneration identity, privacy of
fixture bytes and diagnostics, and a repeat cycle for heap and thread bounds.

Mutation controls are scratch copies only; committed fixtures and production sources
are hashed before and after and must be byte-identical.

Instrumented mirror: app/src/androidTest/java/com/devtunasoft/tuneros/qa52/. Its
manifest entry is QA-52 only; an integrator must merge sibling instrumentation entries.
Device execution is BLOCKED without an authorized adb target and is never claimed from
an offline compile.
