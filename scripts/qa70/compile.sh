#!/usr/bin/env bash
# QA-70 offline K2 compile: gate + shared mirror + stub + all real core sources + one prod source set.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
JAVA="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java.exe"
PROD_DIR="${1:?usage: compile.sh <prod-dir> <build-dir>}"
BUILD="${2:?usage: compile.sh <prod-dir> <build-dir>}"
CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"
CP="$(find "$CACHE" -type f -name '*.jar' 2>/dev/null \
  | grep -E 'kotlin-compiler-embeddable-2\.0\.21|kotlin-stdlib-2\.0\.21|kotlin-script-runtime-2\.0\.21|trove4j|coroutines-core-jvm-1\.6\.4|annotations-13\.0' \
  | tr '\n' ';')"
mkdir -p "$BUILD" || exit 1
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
mapfile -t CORE_FILES < <(find "$CORE" -maxdepth 1 -type f -name '*.kt' | LC_ALL=C sort)
"$JAVA" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$CP" -d "$BUILD" \
  "$ROOT/scripts/qa70/Qa70Gate.kt" \
  "$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa70/Qa70MirrorMain.kt" \
  "$ROOT/scripts/qa70/stubs/HttpConnectionStub.kt" \
  "${CORE_FILES[@]}" \
  "$PROD_DIR/AndroidDashCaptureSource.kt" \
  "$PROD_DIR/AndroidHlsCaptureSource.kt" \
  "$PROD_DIR/HttpStatusException.kt"
rc=$?
if [ "$rc" -eq 0 ]; then printf 'COMPILE\tPASS\n'; else printf 'COMPILE\tFAIL\trc=%s\n' "$rc"; fi
exit "$rc"
