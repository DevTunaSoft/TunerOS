#!/usr/bin/env bash
# QA-69 JVM run of the Android-free instrumented mirror, before any device attempt.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java.exe"
CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
CP="$(find "$CACHE" -type f -name '*.jar' 2>/dev/null \
  | grep -E 'kotlin-compiler-embeddable-2\.0\.21|kotlin-stdlib-2\.0\.21|kotlin-script-runtime-2\.0\.21|trove4j|coroutines-core-jvm-1\.6\.4|annotations-13\.0' \
  | tr '\n' ';')"
STDLIB="$(find "$CACHE" -type f -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)"
SCRATCH="$(cygpath -m "${TMPDIR:-/tmp}")/qa69_run"
BUILD="$SCRATCH/mirror"
mkdir -p "$BUILD"
"$JAVA" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$STDLIB" -d "$BUILD" \
  "$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa69/Qa69Mirror.kt" \
  > "$SCRATCH/mirror-compile.txt" 2>&1
crc=$?
if [ "$crc" -ne 0 ]; then
  printf '%b\n' "MIRROR\tCOMPILE_FAIL\trc=$crc"
  exit 1
fi
timeout 120 "$JAVA" -Xmx96m -cp "$BUILD;$STDLIB" \
  com.devtunasoft.tuneros.qa69.Qa69Mirror > "$SCRATCH/mirror-report.txt" 2>&1
mrc=$?
summary="$(grep '^SUMMARY' "$SCRATCH/mirror-report.txt" | tail -1 | tr -d '\r')"
checks="$(grep -c '^CHECK.*PASS' "$SCRATCH/mirror-report.txt")"
if [ "$mrc" -eq 0 ] && printf '%s' "$summary" | grep -q 'fail=0'; then
  printf '%b\n' "MIRROR\tPASS\t$summary\tchecks=$checks"
  exit 0
fi
printf '%b\n' "MIRROR\tFAIL\trc=$mrc\t$summary"
exit 1
