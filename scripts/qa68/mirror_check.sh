#!/usr/bin/env bash
# QA-68 JVM driver for the Android-free instrumented mirror (fast feedback before AGP).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java.exe"
CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
CP="$(find "$CACHE" -type f -name '*.jar' 2>/dev/null \
  | grep -E 'kotlin-compiler-embeddable-2\.0\.21|kotlin-stdlib-2\.0\.21|kotlin-script-runtime-2\.0\.21|trove4j|coroutines-core-jvm-1\.6\.4|annotations-13\.0' \
  | tr '\n' ';')"
STDLIB="$(find "$CACHE" -type f -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)"
BUILD="$(cygpath -m "${TMPDIR:-/tmp}")/qa68_mirror_build"
mkdir -p "$BUILD"
"$JAVA" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$CP" -d "$BUILD" \
  "$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa68/Qa68MirrorMain.kt"
crc=$?
if [ "$crc" -ne 0 ]; then
  printf 'MIRROR_COMPILE\tFAIL\trc=%s\n' "$crc"
  exit "$crc"
fi
printf 'MIRROR_COMPILE\tPASS\n'
timeout 120 "$JAVA" -Xmx96m -cp "$BUILD;$STDLIB" com.devtunasoft.tuneros.qa68.Qa68MirrorMain
exit $?
