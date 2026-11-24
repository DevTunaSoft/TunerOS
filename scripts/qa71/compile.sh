#!/usr/bin/env bash
# QA-71 offline K2 compiler helper. No dependencies are added to the project.
set -uo pipefail

say() { printf '%b\n' "$1"; }
JH="${JAVA_HOME:-C:/Users/X1/AppData/Local/hermes/cache/scratch/jdk17/jdk-17.0.21.1+2}/bin/java"

discover() {
  find C:/Users/X1/.gradle/caches/modules-2/files-2.1 -type f -name "$1" 2>/dev/null | head -1
}

compiler="$(discover 'kotlin-compiler-embeddable-2.0.21.jar')"
stdlib="$(discover 'kotlin-stdlib-2.0.21.jar')"
scriptrt="$(discover 'kotlin-script-runtime-2.0.21.jar')"
trove="$(discover 'trove4j-1.0.20200330.jar')"
coroutines="$(discover 'kotlinx-coroutines-core-jvm-1.6.4.jar')"
annotations="$(discover 'annotations-13.0.jar')"

for jar in "$compiler" "$stdlib" "$scriptrt" "$trove" "$coroutines" "$annotations"; do
  if [ -z "$jar" ]; then
    say 'COMPILE\tBLOCKED\tmissing-offline-compiler-jar'
    exit 2
  fi
done

cp_native() {
  while IFS= read -r line; do
    cygpath -m "$line" | tr '\n' ';'
  done
}

CP="$(printf '%s\n%s\n%s\n%s\n%s\n%s\n' "$compiler" "$stdlib" "$scriptrt" "$trove" "$coroutines" "$annotations" | cp_native)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2>/dev/null || pwd; })"
OUT="$1"
shift
OUT_NATIVE="$(cygpath -m "$OUT")"
SRCS=""
for src in "$@"; do
  SRCS="$SRCS $(cygpath -m "$src")"
done

mkdir -p "$OUT"
if ! timeout 240 "$JH" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -classpath "$CP" -d "$OUT_NATIVE" $SRCS \
  > "$OUT/compiler.log" 2>&1; then
  say 'COMPILE\tFAIL\tsee-compiler-log'
  exit 1
fi
say 'COMPILE\tPASS\tK2-offline'
