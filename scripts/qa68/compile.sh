#!/usr/bin/env bash
# QA-68 offline K2 compile helper: compile gate + stubs + real production into a scratch build dir.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java.exe"
PROD="${1:?usage: compile.sh <prod-source> <build-dir>}"
BUILD="${2:?usage: compile.sh <prod-source> <build-dir>}"

CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
CP="$(find "$CACHE" -type f -name '*.jar' 2>/dev/null \
  | grep -E 'kotlin-compiler-embeddable-2\.0\.21|kotlin-stdlib-2\.0\.21|kotlin-script-runtime-2\.0\.21|trove4j|coroutines-core-jvm-1\.6\.4|annotations-13\.0' \
  | tr '\n' ';')"

mkdir -p "$BUILD" || exit 1
"$JAVA" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$CP" -d "$BUILD" \
  "$ROOT/scripts/qa68/Qa68Gate.kt" \
  "$ROOT/scripts/qa68/stubs/Media3CommonStub.kt" \
  "$ROOT/scripts/qa68/stubs/Media3DatasourceStub.kt" \
  "$PROD" \
  "$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core/StreamDoctor.kt"
rc=$?
if [ "$rc" -ne 0 ]; then
  printf 'COMPILE\tFAIL\trc=%s\n' "$rc"
else
  printf 'COMPILE\tPASS\n'
fi
exit "$rc"
