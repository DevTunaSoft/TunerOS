#!/usr/bin/env bash
# QA-27 Kotlin mirror JVM check via the cached offline K2 compiler.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
say() { printf '%b\n' "$1"; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && native_pwd)"
JH_DEFAULT="C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2"
JH="${JAVA_HOME:-$JH_DEFAULT}/bin"
CACHE="C:/Users/X1/.gradle/caches/modules-2/files-2.1"

find_jar() { find "$1" -maxdepth 5 -type f -name "$2" 2>/dev/null | head -1; }
COMP="$(find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-2.0.21.jar')"
STDLIB="$(find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-2.0.21.jar')"
SCRIPT="$(find_jar "$CACHE/org.jetbrains.kotlin/kotlin-script-runtime" 'kotlin-script-runtime-2.0.21.jar')"
TROVE="$(find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" 'trove4j-*.jar')"
COROUTINE="$(find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" 'kotlinx-coroutines-core-jvm-*.jar')"
ANN="$(find_jar "$CACHE/org.jetbrains/annotations" 'annotations-13.0.jar')"
if [ -z "$COMP" ] || [ -z "$STDLIB" ]; then
  say 'BLOCKED\tqa27-kotlin-mirror\toffline K2 jars unavailable'
  exit 2
fi
native() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }
CP="$(native "$COMP")"
for j in "$STDLIB" "$SCRIPT" "$TROVE" "$COROUTINE" "$ANN"; do
  [ -n "$j" ] && CP="$CP;$(native "$j")"
done

B="$(native "$HOME/AppData/Local/hermes/cache/scratch/qa27-run/kmirror")"
rm -rf "$B"; mkdir -p "$B"
SRC="$(native "$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa27/Qa27Assertions.kt")"
if ! timeout 240 "$JH/java" -Xmx256m -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -classpath "$CP" -d "$B" "$SRC" > "${TMPDIR:-/tmp}/qa27-kmirror-compile.log" 2>&1; then
  say 'FAIL\tqa27-kotlin-mirror\tcompile'
  grep -E '(error:|warning:)' "${TMPDIR:-/tmp}/qa27-kmirror-compile.log" | head -n 8
  exit 1
fi
LOG="${TMPDIR:-/tmp}/qa27-kmirror.log"
if ! timeout 90 "$JH/java" -Xmx64m -cp "$B;$(native "$STDLIB")" \
    com.devtunasoft.tuneros.qa27.Qa27Assertions > "$LOG" 2>&1; then
  say 'FAIL\tqa27-kotlin-mirror\texit-nonzero'
  cat "$LOG"
  exit 1
fi
N="$(awk -F '\t' '$1=="CHECK" && $3=="PASS"{n++} END{print n+0}' "$LOG")"
if [ "$N" -ne 7 ]; then
  say "FAIL\tqa27-kotlin-mirror\tchecks=$N expected=7"
  cat "$LOG"
  exit 1
fi
say "PASS\tqa27-kotlin-mirror\tchecks=$N"
cat "$LOG"
