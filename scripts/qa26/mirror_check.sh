#!/usr/bin/env bash
# QA-26 JVM mirror check: compile the shared Android-free contract with the
# real production sources and run its plain main() before any device attempt.
set -uo pipefail
native_pwd() { pwd -W 2>/dev/null || pwd; }
native_dir() { (cd "$1" && native_pwd); }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '%b\n' "$1"; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && native_pwd)"
ROOT="$(cd "$HERE/../.." && native_pwd)"
CONTRACT="$ROOT/app/src/androidTest/java/com/devtunasoft/tuneros/qa26/Qa26Contract.kt"
CORE="$ROOT/core/src/main/kotlin/com/devtunasoft/tuneros/core"
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin"
if [ ! -x "$JH/java" ]; then say 'BLOCKED\tqa26-mirror\tno JDK 17'; exit 2; fi

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
JARS=$(find "$CACHE/org.jetbrains.kotlin" \
    \( -name 'kotlin-compiler-embeddable-2.0.21.jar' -o -name 'kotlin-stdlib-2.0.21.jar' \
       -o -name 'kotlin-script-runtime-2.0.21.jar' \) 2>/dev/null;
  find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null;
  find "$CACHE/org.jetbrains/annotations" -name 'annotations-*.jar' 2>/dev/null)
STDLIB_RAW=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.21.jar' 2>/dev/null | head -1)
CP=''
for jar in $JARS; do CP="${CP}$(native_path "$jar");"; done
STDLIB="$(native_path "$STDLIB_RAW")"
if [ -z "$CP" ] || [ -z "$STDLIB_RAW" ]; then
  say 'BLOCKED\tqa26-mirror\tno offline Kotlin 2.0.21 jars'
  exit 2
fi

BUILD="${TMPDIR:-/tmp}/qa26-mirror-build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
OUT="$(native_dir "$BUILD/classes")"
if ! timeout 300 "$JH/java" -Xmx768m -cp "$CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$CP$OUT" -d "$OUT" \
    "$(native_path "$CORE/DashManifest.kt")" "$(native_path "$CORE/DashSegmentIndex.kt")" \
    "$(native_path "$CORE/HlsPlaylistParserCore.kt")" "$(native_path "$CORE/BoundedLines.kt")" \
    "$(native_path "$CONTRACT")" > "$BUILD/compile.log" 2>&1; then
  say 'FAIL\tqa26-mirror-compile\tsee scratch qa26-mirror-build/compile.log'
  grep -E 'error:' "$BUILD/compile.log" | head -n 6
  exit 1
fi
mkdir -p "$BUILD/fixture"
FIX="$(native_path "$BUILD/fixture")"
timeout 180 "$JH/java" -Xmx96m -cp "$OUT;$STDLIB" \
  com.devtunasoft.tuneros.qa26.Qa26Contract generate "$FIX" > "$BUILD/generate.log" 2>&1
if [ "$?" -ne 0 ]; then say 'FAIL\tqa26-mirror-generate'; exit 1; fi
timeout 180 "$JH/java" -Xmx96m -cp "$OUT;$STDLIB" \
  com.devtunasoft.tuneros.qa26.Qa26Contract mirror "$FIX" > "$BUILD/mirror.log" 2>&1
status=$?
if [ "$status" -ne 0 ]; then
  say 'FAIL\tqa26-mirror\tsee scratch qa26-mirror-build/mirror.log'
  grep -E '^MIRROR' "$BUILD/mirror.log" | head -n 8
  exit 1
fi
checks=$(awk -F '\t' '/^MIRROR/ && $2=="PASS" {n++} END {print n+0}' "$BUILD/mirror.log")
if [ "$checks" -ne 5 ]; then
  say "FAIL\tqa26-mirror-count\tpass=$checks expected=5"
  exit 1
fi
grep '^MIRROR_SUMMARY' "$BUILD/mirror.log"
say "MIRROR\tPASS\tchecks=$checks\theap=96m"
exit 0
