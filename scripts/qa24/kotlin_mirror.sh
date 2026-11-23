#!/usr/bin/env bash
# QA-24: compile and run the Android-free Kotlin mirror on the JVM, bounded.
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)
REPO=$(cd "$SCRIPT_DIR/../.." && pwd -W 2>/dev/null || pwd)
MIRROR="$REPO/app/src/androidTest/java/com/devtunasoft/tuneros/qa24/Qa24Contract.kt"

export JAVA_HOME='C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2'
export PATH="/c/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2/bin:$PATH"

cache='C:/Users/X1/.gradle/caches/modules-2/files-2.1'
compile_jar=$(find "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21" -name '*.jar' | head -1)
script_jar=$(find "$cache/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21" -name '*.jar' | head -1)
stdlib_jar=$(find "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -name 'kotlin-stdlib-2.0.21.jar' | head -1)
trove_jar=$(find "$cache/org.jetbrains.intellij.deps/trove4j" -name '*.jar' | head -1)
coroutines_jar=$(find "$cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' | head -1)
annotations_jar=$(find "$cache/org.jetbrains/annotations" -name '*.jar' | head -1)
for jar in "$compile_jar" "$script_jar" "$stdlib_jar" "$trove_jar" "$coroutines_jar" "$annotations_jar"; do
  if [ -z "$jar" ]; then
    printf '%b\n' "MIRROR\tBLOCKED\treason=missing_kotlin_jar"
    exit 2
  fi
done
cp_native=$(for jar in "$compile_jar" "$script_jar" "$stdlib_jar" "$trove_jar" "$coroutines_jar" "$annotations_jar"; do
  cygpath -m "$jar"
done | paste -sd';' -)
stdlib_native=$(cygpath -m "$stdlib_jar")

WORK_BASH=$(mktemp -d "${TMPDIR:-$HOME}/qa24-mirror.XXXXXX") || exit 1
OUT=$(cygpath -m "$WORK_BASH/classes")
SRC=$(cygpath -m "$MIRROR")
mkdir -p "$OUT"

timeout 180 java -Xmx96m -cp "$cp_native" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$cp_native" -d "$OUT" "$SRC" > "$WORK_BASH/compile.log" 2>&1
if [ $? -ne 0 ]; then
  printf '%b\n' 'MIRROR\tFAIL\treason=compile'
  exit 1
fi

timeout 180 java -Xmx96m -cp "$OUT;$stdlib_native" \
  com.devtunasoft.tuneros.qa24.Qa24Contract > "$WORK_BASH/report.log" 2>&1
status=$?
failed=$(awk -F'\t' '$1 == "FAIL" { n++ } END { print (n == "" ? 0 : n) }' "$WORK_BASH/report.log")
summary=$(awk -F'\t' '$1 == "SUMMARY" { print $2 "\t" $3 }' "$WORK_BASH/report.log")
digest_ok=$(awk -F'\t' '$1 == "FAIL" && ($2 == "MIRROR_SORTED_DIGEST" || $2 == "MIRROR_DIGEST") { n++ } END { print (n == "" ? 0 : n) }' "$WORK_BASH/report.log")
if [ "$status" -eq 0 ] && [ "$failed" -eq 0 ] && [ "$digest_ok" -eq 0 ]; then
  printf '%b\n' "MIRROR\tPASS\texit=$status\tfailed=$failed\t$summary"
  exit 0
fi
printf '%b\n' "MIRROR\tFAIL\texit=$status\tfailed=$failed\t$summary"
exit 1
