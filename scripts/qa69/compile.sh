#!/usr/bin/env bash
# QA-69 offline K2 compile helper: compile the shared mirror, scratch stubs, real production and gate.
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
  "$ROOT/scripts/qa69/Qa69Gate.kt" \
  "$ROOT/scripts/qa69/stubs/Context.kt" \
  "$ROOT/scripts/qa69/stubs/ContentValues.kt" \
  "$ROOT/scripts/qa69/stubs/Cursor.kt" \
  "$ROOT/scripts/qa69/stubs/SQLiteDatabase.kt" \
  "$ROOT/scripts/qa69/stubs/SQLiteOpenHelper.kt" \
  "$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa69/Qa69Mirror.kt" \
  "$PROD"
rc=$?
if [ "$rc" -eq 0 ]; then
  printf '%b\n' "COMPILE\tPASS"
else
  printf '%b\n' "COMPILE\tFAIL\trc=$rc"
fi
exit "$rc"
