# QA-55 bounded host gate

Slice: bounded XMLTV category/genre token admission and normalization with
fail-closed malformed tokens.

The gate is deterministic and dependency-free at run time. It compiles one
Kotlin source set with the offline Kotlin 2.0.21 compiler jars plus the real
production units `GuideParser.kt`, `Models.kt` and `XmltvProlog.kt`, then:
  - regenerates a bounded scratch fixture byte for byte and pins its sha256,
  - runs negative controls whose first failure code is pinned,
  - proves all scratch production and fixture bytes are restored,
  - reruns unchanged GREEN under `-Xmx96m` with zero thread drift,
  - proves corrupt XMLTV documents fail closed in the real parser,
  - scans every diagnostic for URL, credential, secret, PIN or payload markers.

Fixture data uses invented channel names and scratch tokens only. No provider
address, credential, PIN or recording payload is stored or emitted.

Commands:
  bash scripts/qa55/run.sh generate   # write the pinned fixture
  bash scripts/qa55/run.sh verify     # RED controls, restore proof, GREEN
  bash scripts/qa55/run.sh gate-only  # GREEN gate alone
  bash scripts/qa55/agp_compile.sh    # offline AGP compile and assemble
